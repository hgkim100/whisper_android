package com.hgkim.whisperandroid.ui

import app.cash.turbine.test
import com.hgkim.whisperandroid.audio.AudioSource
import com.hgkim.whisperandroid.model.ModelDownloader
import com.hgkim.whisperandroid.model.ModelEntry
import com.hgkim.whisperandroid.model.ModelStore
import com.hgkim.whisperandroid.whisper.FakeTranscriber
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Smoke-level regression guards for [MainViewModel] after Task #8.
 *
 * Coverage in this file is intentionally shallow — broader state-machine
 * coverage (recording → transcribing → result transitions, error recovery,
 * retry semantics) is the test reviewer's responsibility per role split,
 * tracked alongside Task #11 follow-ups.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private val entry = ModelEntry(
        id = "ggml-tiny.en",
        filename = "ggml-tiny.en.bin",
        url = "https://example.com/ggml-tiny.en.bin",
        sizeBytes = 1L,
        sha256 = "a".repeat(64),
    )

    @Before
    fun setUp() {
        // viewModelScope launches on Main; substitute with a deterministic dispatcher.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newVm(
        transcriber: FakeTranscriber = FakeTranscriber(),
        modelReady: Boolean = true,
    ): MainViewModel {
        val store = mockk<ModelStore>(relaxed = true).apply {
            every { isModelReady(entry) } returns modelReady
        }
        val downloader = mockk<ModelDownloader>(relaxed = true)
        val audio = mockk<AudioSource>(relaxed = true)
        return MainViewModel(
            transcriber = transcriber,
            newAudioSource = { audio },
            downloader = downloader,
            modelStore = store,
            modelEntry = entry,
        )
    }

    @Test
    fun `init - lands on Idle when model is already on disk`() = runTest {
        val vm = newVm(modelReady = true)
        advanceUntilIdle()
        assertEquals(UiState.Idle, vm.uiState.value)
    }

    @Test
    fun `uiState first emission via turbine - Idle`() = runTest {
        val vm = newVm(modelReady = true)
        advanceUntilIdle()
        vm.uiState.test {
            assertEquals(UiState.Idle, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onPermissionResult false - transitions to Error PermissionDenied`() = runTest {
        val vm = newVm(modelReady = true)
        advanceUntilIdle()
        vm.onPermissionResult(granted = false)
        val s = vm.uiState.value
        assertTrue("expected Error, got $s", s is UiState.Error)
        assertEquals(ErrorKind.PermissionDenied, (s as UiState.Error).kind)
    }
}
