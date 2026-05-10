package com.hgkim.whisperandroid.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Production [AudioSource] backed by [AudioRecord].
 *
 * Capture format is fixed: **16 kHz, mono, 16-bit signed PCM,
 * `MediaRecorder.AudioSource.VOICE_RECOGNITION`** — the combination that
 * whisper.cpp expects without resampling.
 *
 * Lifecycle:
 *  1. Caller (the ViewModel) confirms `RECORD_AUDIO` permission. If missing,
 *     [start] throws [IllegalStateException]; this class does **not** prompt.
 *  2. `start().collect { … }` — emits ~50 ms `ShortArray` chunks until the
 *     coroutine is cancelled, [stop] is called, or the 60 s ceiling is hit.
 *  3. `stop()` — releases the underlying [AudioRecord] and returns the
 *     accumulated PCM normalised to `Float` samples in `[-1, 1]`.
 *
 * **60 s guard** is enforced in-engine via [PcmBuffer]: when the accumulator
 * fills, the flow's collection loop exits naturally so [stop] still returns
 * the recorded prefix. Callers can additionally race a `withTimeout` if a
 * tighter deadline is needed.
 *
 * Single-shot — create a new instance per recording session.
 */
class AudioRecorderImpl : AudioSource {

    private val buffer = PcmBuffer()

    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var stopRequested: Boolean = false

    @SuppressLint("MissingPermission")  // permission check is the caller's responsibility (§ design note)
    override fun start(): Flow<ShortArray> = flow {
        check(recorder == null) { "AudioRecorderImpl.start() called twice on the same instance" }

        val minBuf = AudioRecord.getMinBufferSize(
            Pcm.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
            error("AudioRecord.getMinBufferSize returned $minBuf")
        }
        // Doubled, with a 4096-byte floor (see Task #7 spec).
        val bufBytes = maxOf(minBuf * 2, MIN_BUFFER_BYTES)

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                Pcm.SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufBytes,
            )
        } catch (se: SecurityException) {
            // RECORD_AUDIO not granted — caller failed their contract.
            throw IllegalStateException(
                "RECORD_AUDIO permission missing; ViewModel must request it before AudioRecorderImpl.start()",
                se,
            )
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            error("AudioRecord failed to initialize (state=${rec.state})")
        }

        recorder = rec
        stopRequested = false
        rec.startRecording()

        try {
            val chunk = ShortArray(Pcm.CHUNK_SAMPLES)
            while (!stopRequested && !buffer.isFull) {
                val read = rec.read(chunk, 0, chunk.size)
                if (read < 0) {
                    error("AudioRecord.read() failed with code $read")
                }
                if (read == 0) continue

                val accepted = buffer.append(chunk, read)

                // Hand the samples to downstream as a snapshot copy so the
                // collector can't observe a later overwrite of the chunk buffer.
                emit(if (read == chunk.size) chunk.copyOf() else chunk.copyOf(read))

                if (!accepted) break  // hit the 60 s ceiling
            }
        } finally {
            // Flow body exiting (stop, cancel, or ceiling): leave the AudioRecord
            // stopped but not released, so stop() can drain & return the FloatArray.
            // release happens in stop().
            try {
                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop()
            } catch (_: IllegalStateException) {
                // already stopped — ignore
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun stop(): FloatArray {
        stopRequested = true
        val rec = recorder
        recorder = null
        if (rec != null) {
            if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                runCatching { rec.stop() }
            }
            rec.release()
        }
        return buffer.toFloatArray()
    }

    companion object {
        /** Lower bound on the AudioRecord internal buffer (Task #7 spec). */
        const val MIN_BUFFER_BYTES = 4096
    }
}
