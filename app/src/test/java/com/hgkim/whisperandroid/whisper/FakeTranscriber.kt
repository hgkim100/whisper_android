package com.hgkim.whisperandroid.whisper

import kotlinx.coroutines.delay

/**
 * Test fixture implementing [Transcriber] without any native (whisper.cpp) dependency.
 *
 * Lives in `src/test` so the production APK doesn't pull it in. Exposed for both
 * this PR's unit tests and the upcoming Task #8 ViewModel state-machine tests.
 *
 * @param response       lambda mapping the input PCM to the returned text.
 * @param loadDelayMs    optional `delay()` on [load] (use with `runTest` for virtual time).
 * @param transcribeDelayMs optional `delay()` on [transcribe].
 * @param failOnLoad     if true, [load] throws.
 * @param failOnTranscribe if true, [transcribe] throws.
 */
class FakeTranscriber(
    private val response: (FloatArray) -> String = { "Hello world." },
    private val loadDelayMs: Long = 0L,
    private val transcribeDelayMs: Long = 0L,
    private val failOnLoad: Boolean = false,
    private val failOnTranscribe: Boolean = false,
) : Transcriber {

    var loadCalls: Int = 0
        private set
    var transcribeCalls: Int = 0
        private set
    var releaseCalls: Int = 0
        private set
    var loaded: Boolean = false
        private set

    override suspend fun load() {
        loadCalls++
        if (loadDelayMs > 0) delay(loadDelayMs)
        if (failOnLoad) error("FakeTranscriber: load() failed")
        loaded = true
    }

    override suspend fun transcribe(pcm: FloatArray): String {
        transcribeCalls++
        check(loaded) { "FakeTranscriber.transcribe() called before load()" }
        if (transcribeDelayMs > 0) delay(transcribeDelayMs)
        if (failOnTranscribe) error("FakeTranscriber: transcribe() failed")
        return response(pcm)
    }

    override fun release() {
        releaseCalls++
        loaded = false
    }
}
