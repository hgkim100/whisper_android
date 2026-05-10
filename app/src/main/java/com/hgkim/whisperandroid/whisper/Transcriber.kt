package com.hgkim.whisperandroid.whisper

/**
 * Engine-agnostic transcription contract.
 *
 * Used by [com.hgkim.whisperandroid.ui.MainViewModel] so production code can depend
 * on this interface and tests can swap in a fake (see Task #8 / #11).
 */
interface Transcriber {

    /** Load the underlying model. Throws on failure. */
    suspend fun load()

    /**
     * Run inference on a normalised float32 PCM buffer (16 kHz mono, samples in `[-1, 1]`).
     * May return an empty string for silent / unintelligible audio.
     */
    suspend fun transcribe(pcm: FloatArray): String

    /** Free underlying resources. Implementations should be safe to call once. */
    fun release()
}
