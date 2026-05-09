package com.hgkim.whisperandroid.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Main screen UI state.
 *
 * 1단계는 push-to-talk 일괄 처리 (ARCHITECTURE.md §3.4).
 * 본 클래스는 Task #4 스켈레톤이며, 실제 상태 전이/이벤트 핸들러는 Task #8에서 채운다.
 */
sealed interface UiState {
    data object Idle : UiState
    data class ModelDownloading(val progress: Float) : UiState
    data class Recording(val elapsedMs: Long, val rms: Float) : UiState
    data object Transcribing : UiState
    data class Result(val text: String) : UiState
    data class Error(val message: String) : UiState
}

class MainViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Task #8에서 구현. 현재는 스텁. */
    fun onMicTap() {
        // no-op (skeleton)
    }
}
