package com.hgkim.whisperandroid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hgkim.whisperandroid.ui.theme.WhisperAndroidTheme

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onMicTap: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetryDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    MainScreenContent(
        state = state,
        onMicTap = onMicTap,
        onOpenSettings = onOpenSettings,
        onRetryDownload = onRetryDownload,
        modifier = modifier,
    )
}

@Composable
private fun MainScreenContent(
    state: UiState,
    onMicTap: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetryDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = "Status: ${state.label()}")

        if (state is UiState.ModelDownloading) {
            LinearProgressIndicator(
                progress = { state.pct / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(text = "${state.pct}%")
        }

        SelectionContainer(
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            Text(
                text = state.transcriptOrEmpty(),
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            )
        }

        Button(
            onClick = onMicTap,
            enabled = state.micEnabled(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(text = if (state is UiState.Recording) "Stop" else "Record") }

        if (state is UiState.Error && state.kind == ErrorKind.PermissionDenied) {
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Open Settings")
            }
        }
        if (state is UiState.Error && state.kind == ErrorKind.DownloadFailed) {
            OutlinedButton(onClick = onRetryDownload, modifier = Modifier.fillMaxWidth()) {
                Text("Retry download")
            }
        }
        Spacer(Modifier.height(0.dp))
    }
}

private fun UiState.label(): String = when (this) {
    is UiState.Idle -> "Ready"
    is UiState.ModelDownloading -> "Downloading model"
    is UiState.Recording -> "Recording"
    is UiState.Transcribing -> "Transcribing"
    is UiState.Result -> "Done"
    is UiState.Error -> "Error"
}

private fun UiState.transcriptOrEmpty(): String = when (this) {
    is UiState.Result -> text
    is UiState.Error -> message
    else -> ""
}

private fun UiState.micEnabled(): Boolean = when (this) {
    is UiState.Idle, is UiState.Recording, is UiState.Result -> true
    is UiState.Error -> kind != ErrorKind.PermissionDenied && kind != ErrorKind.DownloadFailed
    is UiState.ModelDownloading, is UiState.Transcribing -> false
}

@Preview(showBackground = true, name = "Idle")
@Composable
private fun PreviewIdle() = WhisperAndroidTheme {
    MainScreenContent(UiState.Idle, {}, {}, {})
}

@Preview(showBackground = true, name = "Downloading")
@Composable
private fun PreviewDownloading() = WhisperAndroidTheme {
    MainScreenContent(UiState.ModelDownloading(42), {}, {}, {})
}

@Preview(showBackground = true, name = "Result")
@Composable
private fun PreviewResult() = WhisperAndroidTheme {
    MainScreenContent(UiState.Result("Hello world."), {}, {}, {})
}

@Preview(showBackground = true, name = "Permission denied")
@Composable
private fun PreviewPermDenied() = WhisperAndroidTheme {
    MainScreenContent(
        UiState.Error("Microphone permission is required.", ErrorKind.PermissionDenied),
        {}, {}, {},
    )
}
