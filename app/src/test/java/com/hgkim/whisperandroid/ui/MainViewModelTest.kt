package com.hgkim.whisperandroid.ui

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression guards for [MainViewModel].
 *
 * Skeleton-stage tests: the ViewModel only exposes the initial [UiState] for now.
 * Once Task #8 fills in the state machine, more transitions will be added here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    @Test
    fun `initial state is Idle (synchronous)`() {
        val vm = MainViewModel()
        assertEquals(UiState.Idle, vm.uiState.value)
    }

    @Test
    fun `uiState flow first emission is Idle (turbine smoke)`() = runTest {
        val vm = MainViewModel()
        vm.uiState.test {
            assertEquals(UiState.Idle, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onMicTap is a no-op in skeleton (state stays Idle)`() = runTest {
        val vm = MainViewModel()
        vm.onMicTap()
        assertEquals(UiState.Idle, vm.uiState.value)
    }
}
