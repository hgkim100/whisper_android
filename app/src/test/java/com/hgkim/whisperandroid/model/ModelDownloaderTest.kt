package com.hgkim.whisperandroid.model

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Tests for [ModelDownloader] using OkHttp's [MockWebServer] and a mocked
 * [ModelStore] (mockk).
 *
 * Coverage:
 *  - Happy path: Starting → Running(s) → Completed, `.part` removed, final file exists
 *    with correct content + length.
 *  - HTTP non-2xx → Failed("HTTP …"), no `.part` left behind.
 *  - SHA-256 mismatch → Failed("SHA-256 mismatch …"), `.part` deleted.
 *  - Size sanity (>5%) mismatch → Failed("Size mismatch …"), `.part` deleted.
 */
class ModelDownloaderTest {

    private lateinit var server: MockWebServer
    private lateinit var tmpRoot: File
    private lateinit var partFile: File
    private lateinit var finalFile: File
    private lateinit var store: ModelStore

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        tmpRoot = kotlin.io.path.createTempDirectory("modeldownloader-test-").toFile()
        partFile = File(tmpRoot, "test.bin.part")
        finalFile = File(tmpRoot, "test.bin")
        store = mockk(relaxed = false)
    }

    @After
    fun tearDown() {
        server.shutdown()
        tmpRoot.deleteRecursively()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun entry(
        url: String,
        sizeBytes: Long,
        sha256: String,
    ) = ModelEntry(
        id = "test",
        filename = "test.bin",
        url = url,
        sizeBytes = sizeBytes,
        sha256 = sha256,
    ).also { e ->
        every { store.partFileFor(e) } returns partFile
        every { store.fileFor(e) } returns finalFile
    }

    private fun bodyOf(bytes: ByteArray): MockResponse {
        val buf = Buffer().apply { write(bytes) }
        return MockResponse().setResponseCode(200).setBody(buf)
    }

    @Test
    fun `download success - emits Starting then Running then Completed and writes finalFile`() = runTest {
        val payload = ByteArray(2_048) { (it % 251).toByte() }
        val sha = sha256Hex(payload)
        val url = server.url("/test.bin").toString()
        val entry = entry(url, payload.size.toLong(), sha)
        server.enqueue(bodyOf(payload))

        val downloader = ModelDownloader(
            client = OkHttpClient(),
            store = store,
            emitEveryBytes = 256L, // force several Running emissions on a 2 KB payload
        )

        val events = downloader.download(entry).toList()

        // First and last
        assertTrue("first event should be Starting, was ${events.first()}",
            events.first() is DownloadProgress.Starting)
        assertTrue("last event should be Completed, was ${events.last()}",
            events.last() is DownloadProgress.Completed)

        // At least one Running between
        val running = events.filterIsInstance<DownloadProgress.Running>()
        assertTrue("expected ≥1 Running event, got ${running.size}", running.isNotEmpty())
        // Running.fraction must be in [0, 1]
        running.forEach {
            assertTrue("fraction ${it.fraction} out of [0,1]", it.fraction in 0f..1f)
        }
        // Final Running.fraction == 1f
        assertEquals(1f, running.last().fraction, 0.001f)

        // Filesystem result
        val completed = events.last() as DownloadProgress.Completed
        assertEquals(finalFile, completed.file)
        assertTrue("final file should exist", finalFile.isFile)
        assertEquals(payload.size.toLong(), finalFile.length())
        assertFalse(".part should have been renamed away", partFile.exists())
        // Bytes match
        assertTrue(finalFile.readBytes().contentEquals(payload))
    }

    @Test
    fun `download HTTP 404 - emits Failed with HTTP code, leaves no part behind`() = runTest {
        val url = server.url("/missing.bin").toString()
        val entry = entry(url, 1024, "a".repeat(64))
        server.enqueue(MockResponse().setResponseCode(404))

        val events = ModelDownloader(OkHttpClient(), store).download(entry).toList()

        // Expected: [Starting, Failed]
        assertEquals(2, events.size)
        assertTrue(events[0] is DownloadProgress.Starting)
        val failed = events[1] as DownloadProgress.Failed
        assertTrue("reason should mention HTTP 404, was '${failed.reason}'",
            failed.reason.contains("HTTP 404"))
        assertFalse(finalFile.exists())
        assertFalse(partFile.exists())
    }

    @Test
    fun `download SHA-256 mismatch - emits Failed and deletes part`() = runTest {
        val payload = ByteArray(1_024) { 0x42 }
        // declare a *different* sha than the actual payload's hash
        val wrongSha = "f".repeat(64)
        val url = server.url("/test.bin").toString()
        val entry = entry(url, payload.size.toLong(), wrongSha)
        server.enqueue(bodyOf(payload))

        val events = ModelDownloader(OkHttpClient(), store).download(entry).toList()

        val failed = events.filterIsInstance<DownloadProgress.Failed>().firstOrNull()
        assertNotNull("expected a Failed event", failed)
        assertTrue("reason should mention SHA-256, was '${failed!!.reason}'",
            failed.reason.contains("SHA-256 mismatch"))
        assertFalse(".part must be cleaned up after sha mismatch", partFile.exists())
        assertFalse("final file must not be promoted on sha mismatch", finalFile.exists())
    }

    @Test
    fun `download size mismatch greater than 5pct - emits Failed and deletes part`() = runTest {
        // declare 10 KB but server returns 1 KB → ~90% off
        val payload = ByteArray(1_024) { 0x11 }
        val sha = sha256Hex(payload)
        val url = server.url("/test.bin").toString()
        val entry = entry(url, sizeBytes = 10_240L, sha256 = sha)
        server.enqueue(bodyOf(payload))

        val events = ModelDownloader(OkHttpClient(), store).download(entry).toList()

        val failed = events.filterIsInstance<DownloadProgress.Failed>().firstOrNull()
        assertNotNull("expected a Failed event", failed)
        assertTrue("reason should mention size mismatch, was '${failed!!.reason}'",
            failed.reason.contains("Size mismatch"))
        assertFalse(".part must be cleaned up after size mismatch", partFile.exists())
        assertFalse(finalFile.exists())
    }
}
