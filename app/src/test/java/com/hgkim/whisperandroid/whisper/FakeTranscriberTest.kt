package com.hgkim.whisperandroid.whisper

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Smoke tests for the in-test [FakeTranscriber] — also doubles as a contract
 * sanity check for the [Transcriber] interface used by the production
 * [WhisperEngine] and the upcoming Task #8 ViewModel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeTranscriberTest {

    @Test
    fun `happy path - load then transcribe then release`() = runTest {
        val fake = FakeTranscriber(response = { "ok" })
        fake.load()
        val out = fake.transcribe(FloatArray(16))
        fake.release()

        assertEquals("ok", out)
        assertEquals(1, fake.loadCalls)
        assertEquals(1, fake.transcribeCalls)
        assertEquals(1, fake.releaseCalls)
        assertFalse("loaded should flip back to false after release", fake.loaded)
    }

    @Test
    fun `transcribe before load - throws IllegalStateException`() = runTest {
        val fake = FakeTranscriber()
        try {
            fake.transcribe(FloatArray(0))
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("before load"))
        }
    }

    @Test
    fun `load with delay - completes under virtual time`() = runTest {
        val fake = FakeTranscriber(loadDelayMs = 10_000L)
        fake.load()
        advanceUntilIdle()
        assertTrue(fake.loaded)
    }

    @Test
    fun `failOnLoad surfaces an exception and leaves loaded false`() = runTest {
        val fake = FakeTranscriber(failOnLoad = true)
        try {
            fake.load()
            fail("expected IllegalStateException from failOnLoad")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("load() failed"))
        }
        assertFalse(fake.loaded)
    }
}
