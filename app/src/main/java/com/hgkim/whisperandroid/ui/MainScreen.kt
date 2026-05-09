package com.hgkim.whisperandroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hgkim.whisperandroid.ui.theme.WhisperAndroidTheme

/**
 * Main screen 스텁 (ARCHITECTURE.md §3.5).
 * 실제 UI(마이크 FAB, 타이머, RMS 미터, Transcript, 모델 뱃지)는 Task #8에서 구현한다.
 */
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Column(
        modifier = modifier.padding(24.dp).fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "Whisper Android")
        Text(text = "state = $state")
    }
}

@Preview(showBackground = true)
@Composable
private fun MainScreenPreview() {
    WhisperAndroidTheme {
        MainScreen(viewModel = MainViewModel())
    }
}
