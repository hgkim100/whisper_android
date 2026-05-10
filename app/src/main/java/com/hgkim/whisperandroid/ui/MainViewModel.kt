package com.hgkim.whisperandroid.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hgkim.whisperandroid.AppContainer
import com.hgkim.whisperandroid.audio.AudioSource
import com.hgkim.whisperandroid.model.DownloadProgress
import com.hgkim.whisperandroid.model.ModelDownloader
import com.hgkim.whisperandroid.model.ModelEntry
import com.hgkim.whisperandroid.model.ModelStore
import com.hgkim.whisperandroid.whisper.Transcriber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state for the single-screen app (ARCHITECTURE.md §3.5).
 *
 * Six exhaustive variants — anything else is a contract violation. No
 * language-selection state ever (§A — English-only, permanent).
 */
sealed interface UiState {
    data object Idle : UiState
    /** Model download in progress; [pct] in `0..100`. */
    data class ModelDownloading(val pct: Int) : UiState
    data object Recording : UiState
    data object Transcribing : UiState
    data class Result(val text: String) : UiState
    /**
     * Recoverable failure surfaced to the user.
     *
     * [kind] disambiguates which retry affordance the UI offers:
     *  - [ErrorKind.PermissionDenied] → "Open Settings"
     *  - [ErrorKind.DownloadFailed]   → "Retry download"
     *  - [ErrorKind.Other]            → generic "Try again" (back to Idle)
     */
    data class Error(val message: String, val kind: ErrorKind = ErrorKind.Other) : UiState
}

enum class ErrorKind { PermissionDenied, DownloadFailed, Other }

/**
 * Owns the single source of truth for the UI ([uiState]) and the three event
 * entry points the screen exposes (`onMicTap`, `onPermissionResult`,
 * `onRetryDownload`).
 *
 * **Lazy model strategy** (per team-lead direction): the ViewModel does **not**
 * download or load the model on construction. The first `onMicTap` triggers
 * the chain `download (if missing) → load → record`, so a user who launches
 * the app without intending to record never pays the 75 MB download cost.
 *
 * Concurrency model:
 *  - All state mutations happen on the main thread (Compose collects).
 *  - Long-running work (download, audio capture, inference) runs on
 *    [viewModelScope]; the underlying [Transcriber] / [AudioSource] /
 *    [ModelDownloader] each own their own dispatcher.
 *
 * Permission contract:
 *  - [onMicTap] assumes the caller has already verified `RECORD_AUDIO`
 *    permission.
 *  - If the user denies permission, [com.hgkim.whisperandroid.MainActivity]
 *    calls [onPermissionResult]`(false)` and we transition to
 *    `Error(kind=PermissionDenied)`.
 */
class MainViewModel(
    private val transcriber: Transcriber,
    private val newAudioSource: () -> AudioSource,
    private val downloader: ModelDownloader,
    private val modelStore: ModelStore,
    private val modelEntry: ModelEntry,
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    @Volatile private var modelLoaded: Boolean = false
    private var captureJob: Job? = null
    private var currentAudio: AudioSource? = null

    // No init block — the ViewModel starts in Idle and stays there until the
    // user taps Record.

    // ----- Events ------------------------------------------------------------

    /** User tapped the Record/Stop button. Caller must already hold RECORD_AUDIO. */
    fun onMicTap() {
        when (val s = _uiState.value) {
            is UiState.Idle, is UiState.Result -> ensureModelThenRecord()
            is UiState.Recording -> stopAndTranscribe()
            is UiState.Error -> when (s.kind) {
                ErrorKind.DownloadFailed -> ensureModelThenRecord()
                ErrorKind.Other -> ensureModelThenRecord()
                // PermissionDenied is recovered via Settings, not the mic button.
                ErrorKind.PermissionDenied -> Unit
            }
            // While downloading or transcribing, button is disabled in the UI.
            is UiState.ModelDownloading, is UiState.Transcribing -> Unit
        }
    }

    /** Result of the runtime permission prompt. */
    fun onPermissionResult(granted: Boolean) {
        if (!granted) {
            _uiState.value = UiState.Error(
                message = "Microphone permission is required to record. Open Settings to grant it.",
                kind = ErrorKind.PermissionDenied,
            )
        }
        // If granted, we don't auto-start — the activity calls onMicTap() next.
    }

    /** Retry the download → load → record chain after a failed model download. */
    fun onRetryDownload() {
        ensureModelThenRecord()
    }

    // ----- Internals ---------------------------------------------------------

    /**
     * Single entry point that drives the full
     * `download (if needed) → load (if needed) → start recording` chain.
     * Each phase emits its own UiState transition; failures land in
     * [UiState.Error] with an appropriate [ErrorKind].
     */
    private fun ensureModelThenRecord() {
        viewModelScope.launch {
            // Phase 1 — make sure the model file is on disk.
            if (!modelStore.isModelReady(modelEntry)) {
                val downloaded = downloadModel()
                if (!downloaded) return@launch  // Failed already emitted
            }
            // Phase 2 — make sure the native context is loaded.
            if (!modelLoaded) {
                val loaded = loadModel()
                if (!loaded) return@launch
            }
            // Phase 3 — begin capture.
            startRecording()
        }
    }

    /** @return true if the model is now on disk; false if Failed was emitted. */
    private suspend fun downloadModel(): Boolean {
        var success = false
        downloader.download(modelEntry).collect { ev ->
            when (ev) {
                is DownloadProgress.Starting -> {
                    _uiState.value = UiState.ModelDownloading(0)
                }
                is DownloadProgress.Running -> {
                    _uiState.value = UiState.ModelDownloading(
                        (ev.fraction * 100).toInt().coerceIn(0, 100),
                    )
                }
                is DownloadProgress.Completed -> {
                    success = true
                }
                is DownloadProgress.Failed -> {
                    _uiState.value = UiState.Error(
                        message = "Model download failed: ${ev.reason}",
                        kind = ErrorKind.DownloadFailed,
                    )
                }
            }
        }
        return success
    }

    /** @return true if the engine is loaded; false if Failed was emitted. */
    private suspend fun loadModel(): Boolean {
        return try {
            transcriber.load()
            modelLoaded = true
            true
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            _uiState.value = UiState.Error(
                message = "Failed to load model: ${t.message ?: t.javaClass.simpleName}",
                kind = ErrorKind.Other,
            )
            false
        }
    }

    private fun startRecording() {
        val audio = newAudioSource()
        currentAudio = audio
        _uiState.value = UiState.Recording
        captureJob = viewModelScope.launch {
            try {
                // Drain chunks; AudioRecorderImpl accumulates via PcmBuffer
                // and stop() returns the FloatArray.
                audio.start().collect { /* discard */ }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _uiState.value = UiState.Error(
                    message = "Recording failed: ${t.message ?: t.javaClass.simpleName}",
                    kind = ErrorKind.Other,
                )
                currentAudio = null
            }
        }
    }

    private fun stopAndTranscribe() {
        val audio = currentAudio ?: run {
            _uiState.value = UiState.Idle
            return
        }
        _uiState.value = UiState.Transcribing
        viewModelScope.launch {
            try {
                captureJob?.cancel()
                val pcm = audio.stop()
                currentAudio = null
                val text = transcriber.transcribe(pcm)
                _uiState.value = UiState.Result(text)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _uiState.value = UiState.Error(
                    message = "Transcription failed: ${t.message ?: t.javaClass.simpleName}",
                    kind = ErrorKind.Other,
                )
            }
        }
    }

    override fun onCleared() {
        captureJob?.cancel()
        if (modelLoaded) {
            transcriber.release()
            modelLoaded = false
        }
        super.onCleared()
    }

    // ----- Factory -----------------------------------------------------------

    companion object {
        /** Create a [ViewModelProvider.Factory] backed by an [AppContainer]. */
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(MainViewModel::class.java)) {
                        "Unknown ViewModel class: $modelClass"
                    }
                    return MainViewModel(
                        transcriber = container.newTranscriber(),
                        newAudioSource = container::newAudioSource,
                        downloader = container.downloader,
                        modelStore = container.modelStore,
                        modelEntry = container.modelEntry,
                    ) as T
                }
            }
    }
}
