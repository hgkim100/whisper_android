package com.hgkim.whisperandroid.whisper

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Production [Transcriber] backed by whisper.cpp through the [WhisperJni] bridge.
 *
 * Lifecycle:
 *  1. Construct with the path to a `ggml-*.bin` file.
 *  2. [load] — opens the native context (off-main).
 *  3. [transcribe] — repeated calls share the loaded context (off-main).
 *  4. [release] — frees the native context. Safe to call multiple times; subsequent
 *     calls are no-ops.
 *
 * Not thread-safe across [transcribe] calls — serialise from the caller side.
 */
class WhisperEngine(
    private val modelPath: String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : Transcriber {

    @Volatile private var ctx: Long = 0L

    override suspend fun load() = withContext(dispatcher) {
        require(ctx == 0L) { "WhisperEngine is already loaded" }
        val handle = WhisperJni.init(modelPath)
        check(handle != 0L) { "Failed to load whisper model: $modelPath" }
        ctx = handle
    }

    override suspend fun transcribe(pcm: FloatArray): String = withContext(dispatcher) {
        val handle = ctx
        check(handle != 0L) { "WhisperEngine.transcribe() called before load() (or after release)" }
        WhisperJni.transcribe(handle, pcm)
    }

    override fun release() {
        val handle = ctx
        if (handle != 0L) {
            ctx = 0L
            WhisperJni.release(handle)
        }
    }
}
