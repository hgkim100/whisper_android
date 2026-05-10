package com.hgkim.whisperandroid.audio

import kotlinx.coroutines.flow.Flow

/**
 * Engine-agnostic recording contract — the bridge between the audio capture
 * pipeline and [com.hgkim.whisperandroid.ui.MainViewModel].
 *
 * Implementations:
 *  - [AudioRecorder] — production, backed by [android.media.AudioRecord].
 *  - [FileAudioSource] — debug fallback that replays a 16 kHz mono WAV from
 *    assets / disk when the emulator microphone is unreliable
 *    (see ARCHITECTURE.md §3.6).
 *
 * Contract:
 *  - One [start] → [stop] cycle per instance. Reusing after [stop] is
 *    implementation-defined.
 *  - [start] returns a cold [Flow] of mono 16-bit PCM chunks (~50 ms each).
 *    The flow completes when [stop] is invoked or capture is cancelled.
 *  - [stop] returns the entire accumulated PCM, normalised to `Float`
 *    samples in `[-1, 1]`, suitable for `whisper_full(...)`.
 */
interface AudioSource {

    /** Begin capture. Each emission is a mono 16-bit PCM chunk. */
    fun start(): Flow<ShortArray>

    /** Stop capture and return the accumulated PCM as float32 in `[-1, 1]`. */
    suspend fun stop(): FloatArray
}
