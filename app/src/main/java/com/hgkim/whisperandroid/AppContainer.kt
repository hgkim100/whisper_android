package com.hgkim.whisperandroid

import android.content.Context
import com.hgkim.whisperandroid.audio.AudioRecorderImpl
import com.hgkim.whisperandroid.audio.AudioSource
import com.hgkim.whisperandroid.model.ModelDownloader
import com.hgkim.whisperandroid.model.ModelEntry
import com.hgkim.whisperandroid.model.ModelManifest
import com.hgkim.whisperandroid.model.ModelStore
import com.hgkim.whisperandroid.whisper.Transcriber
import com.hgkim.whisperandroid.whisper.WhisperEngine
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Application-scoped service container — the lightweight DI seam used by
 * [com.hgkim.whisperandroid.MainActivity] to assemble a [com.hgkim.whisperandroid.ui.MainViewModel].
 *
 * Lives as one lazy instance per process via [WhisperApp.container].
 *
 * Production wiring throughout: real [WhisperEngine] (loaded lazily once the
 * user actually taps Record — see [com.hgkim.whisperandroid.ui.MainViewModel])
 * and real [AudioRecorderImpl].
 */
class AppContainer(context: Context) {

    val manifest: ModelManifest = ModelManifest.load(context)
    val modelEntry: ModelEntry = manifest.defaultModel
    val modelStore: ModelStore = ModelStore(context)

    /**
     * OkHttp client tuned for large model downloads.
     * - readTimeout 60 s (per reviewer-2 follow-up on PR #3): the default 10 s
     *   is too tight for a ~75 MB ggml binary on a slow link.
     * - callTimeout 0 (no overall cap) — we rely on `readTimeout` per chunk.
     */
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    val downloader: ModelDownloader = ModelDownloader(httpClient, modelStore)

    /** Fresh capture session per recording. */
    fun newAudioSource(): AudioSource = AudioRecorderImpl()

    /**
     * Fresh [WhisperEngine] bound to the on-disk model path. The engine is
     * `load()`-ed lazily by the ViewModel after the model file is known to
     * exist (post-download), so constructing it here without doing the file
     * I/O is safe even on first launch.
     */
    fun newTranscriber(): Transcriber =
        WhisperEngine(modelStore.fileFor(modelEntry).absolutePath)
}
