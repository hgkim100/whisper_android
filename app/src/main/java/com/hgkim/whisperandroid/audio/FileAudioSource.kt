package com.hgkim.whisperandroid.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Debug fallback [AudioSource] that replays a 16 kHz mono 16-bit PCM WAV file
 * through the same pipeline (see ARCHITECTURE.md §3.6 — emulator microphone is
 * occasionally unreliable, so a deterministic file-backed source lets us run
 * end-to-end smoke tests).
 *
 * The WAV file itself is **not** committed to the repo; supply one via
 * `adb push <wav> /sdcard/...` or drop it under `app/src/main/assets/test_en.wav`
 * locally and route [openInput] accordingly.
 *
 * Format constraints (matched against the RIFF header on [start]):
 *  - PCM (format code 1)
 *  - 1 channel (mono)
 *  - 16 000 Hz sample rate
 *  - 16-bit samples
 *
 * Anything else throws [IllegalStateException] before any audio is emitted.
 *
 * Behaviour:
 *  - Emits the file in 50 ms chunks, sleeping between emissions so downstream
 *    flows see realistic timing.
 *  - [stop] returns the concatenated PCM normalised to `[-1, 1]`.
 *  - Honours the 60 s ceiling via [PcmBuffer].
 */
class FileAudioSource(
    private val openInput: () -> InputStream,
    private val realTime: Boolean = true,
) : AudioSource {

    private val buffer = PcmBuffer()
    @Volatile private var stopRequested = false

    override fun start(): Flow<ShortArray> = flow {
        stopRequested = false
        buffer.clear()

        val samples = readWav16kMono(openInput())
        var offset = 0
        val chunk = ShortArray(Pcm.CHUNK_SAMPLES)

        while (!stopRequested && offset < samples.size && !buffer.isFull) {
            val n = minOf(chunk.size, samples.size - offset)
            System.arraycopy(samples, offset, chunk, 0, n)
            offset += n

            buffer.append(chunk, n)
            emit(if (n == chunk.size) chunk.copyOf() else chunk.copyOf(n))
            if (realTime) delay(Pcm.CHUNK_MS.toLong())
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun stop(): FloatArray {
        stopRequested = true
        return buffer.toFloatArray()
    }

    // ---- Minimal WAV reader -------------------------------------------------
    // RIFF/WAVE structure. We only support the canonical case the app needs:
    // PCM, 1 channel, 16 kHz, 16-bit. No extensible / float / multichannel.

    private fun readWav16kMono(input: InputStream): ShortArray {
        DataInputStream(input.buffered()).use { ds ->
            val riff = ByteArray(4).also { ds.readFully(it) }
            check(riff.contentEquals("RIFF".toByteArray())) { "Not a RIFF file" }
            ds.skipFully(4)  // chunk size
            val wave = ByteArray(4).also { ds.readFully(it) }
            check(wave.contentEquals("WAVE".toByteArray())) { "Not a WAVE container" }

            // Walk sub-chunks until we find "fmt " and "data".
            var format: Int = -1
            var channels: Int = -1
            var sampleRate: Int = -1
            var bitsPerSample: Int = -1

            while (true) {
                val id = ByteArray(4)
                val read = ds.read(id)
                if (read < 4) break
                val sizeLE = readIntLE(ds)

                when (String(id)) {
                    "fmt " -> {
                        format = readShortLE(ds).toInt() and 0xFFFF
                        channels = readShortLE(ds).toInt() and 0xFFFF
                        sampleRate = readIntLE(ds)
                        ds.skipFully(4)              // byte rate
                        ds.skipFully(2)              // block align
                        bitsPerSample = readShortLE(ds).toInt() and 0xFFFF
                        // Skip any extension bytes
                        val consumed = 16
                        if (sizeLE > consumed) ds.skipFully(sizeLE - consumed)
                    }
                    "data" -> {
                        check(format == 1) { "Unsupported WAV format code: $format (expected PCM)" }
                        check(channels == 1) { "Unsupported channel count: $channels (expected mono)" }
                        check(sampleRate == Pcm.SAMPLE_RATE_HZ) {
                            "Unsupported sample rate: $sampleRate (expected ${Pcm.SAMPLE_RATE_HZ})"
                        }
                        check(bitsPerSample == 16) {
                            "Unsupported bit depth: $bitsPerSample (expected 16)"
                        }
                        val byteCount = sizeLE.coerceAtLeast(0)
                        val sampleCount = byteCount / 2
                        val out = ShortArray(sampleCount)
                        for (i in 0 until sampleCount) {
                            out[i] = readShortLE(ds)
                        }
                        return out
                    }
                    else -> ds.skipFully(sizeLE)
                }
            }
            error("WAV missing 'data' sub-chunk")
        }
    }

    private fun readIntLE(ds: DataInputStream): Int {
        val b0 = ds.readUnsignedByte()
        val b1 = ds.readUnsignedByte()
        val b2 = ds.readUnsignedByte()
        val b3 = ds.readUnsignedByte()
        return (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
    }

    private fun readShortLE(ds: DataInputStream): Short {
        val lo = ds.readUnsignedByte()
        val hi = ds.readUnsignedByte()
        return ((hi shl 8) or lo).toShort()
    }

    @Throws(IOException::class)
    private fun DataInputStream.skipFully(n: Int) {
        var remaining = n
        while (remaining > 0) {
            val skipped = skip(remaining.toLong()).toInt()
            if (skipped <= 0) {
                // Fall back to read-and-discard (skip returns 0 at EOF or with some streams).
                if (read() < 0) throw IOException("Unexpected EOF while skipping $n bytes")
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
    }
}
