package com.hgkim.whisperandroid.whisper

/**
 * Thin Kotlin shim over the native `libwhisper_jni.so` bridge.
 *
 * Three native entrypoints (see app/src/main/cpp/whisper_jni.cpp):
 *  - [init]       — load a ggml model file, return a native context handle (`jlong`). 0 == failure.
 *  - [transcribe] — run greedy English inference on a 16 kHz mono float32 PCM buffer.
 *  - [release]    — free the native context. Caller must invoke exactly once.
 *
 * Language is hard-coded to "en" inside the C++ layer (see ARCHITECTURE.md §A).
 *
 * Singleton (Kotlin `object`) keeps the JNI symbol names short:
 *   `Java_com_hgkim_whisperandroid_whisper_WhisperJni_init`, etc.
 *
 * All three methods block — callers should run them on `Dispatchers.Default`.
 */
internal object WhisperJni {

    init {
        System.loadLibrary("whisper_jni")
    }

    /**
     * Load a ggml whisper model from disk.
     *
     * @param modelPath absolute path to a `ggml-*.bin` file already extracted to internal storage.
     * @return native `whisper_context*` cast to `Long`, or `0L` on failure.
     */
    @JvmStatic external fun init(modelPath: String): Long

    /**
     * Run inference on a single PCM buffer.
     *
     * @param ctx handle returned by [init]. Must be non-zero.
     * @param pcm normalised mono PCM, 16 kHz, samples in `[-1.0, 1.0]`.
     * @return decoded text, possibly empty (silence / failure / 0 ctx).
     */
    @JvmStatic external fun transcribe(ctx: Long, pcm: FloatArray): String

    /**
     * Free the native context. Idempotency is **not** guaranteed — call exactly once.
     */
    @JvmStatic external fun release(ctx: Long)
}
