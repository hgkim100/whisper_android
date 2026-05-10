package com.hgkim.whisperandroid.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Progress / terminal events emitted by [ModelDownloader.download]. */
sealed interface DownloadProgress {
    data class Starting(val entry: ModelEntry) : DownloadProgress
    /**
     * Periodic progress update.
     *
     * @param bytesRead bytes written to the `.part` file so far
     * @param totalBytes either the response Content-Length or the manifest's
     *                   size_bytes when the server didn't advertise a length
     * @param fraction   `bytesRead / totalBytes`, clamped to `[0f, 1f]`
     */
    data class Running(
        val entry: ModelEntry,
        val bytesRead: Long,
        val totalBytes: Long,
        val fraction: Float,
    ) : DownloadProgress
    data class Completed(val entry: ModelEntry, val file: File) : DownloadProgress
    data class Failed(val entry: ModelEntry, val reason: String, val cause: Throwable? = null) :
        DownloadProgress
}

/**
 * Streams a [ModelEntry] from its HTTPS URL into [ModelStore], verifying
 * SHA-256 before promoting `.part` to the final filename.
 *
 * Cold [Flow] — emission begins only when collected. Cancellation aborts the
 * HTTP call and leaves the `.part` file in place; the next download attempt
 * will overwrite it (no resume in Phase 1).
 *
 * Failure modes:
 *  - HTTP non-2xx                  → [DownloadProgress.Failed]
 *  - Length mismatch (>5% off)     → [DownloadProgress.Failed], `.part` deleted
 *  - SHA-256 mismatch              → [DownloadProgress.Failed], `.part` deleted
 *  - I/O exception                 → [DownloadProgress.Failed], `.part` deleted
 */
class ModelDownloader(
    private val client: OkHttpClient = OkHttpClient(),
    private val store: ModelStore,
    /** Minimum bytes between [DownloadProgress.Running] emissions; throttles UI. */
    private val emitEveryBytes: Long = 256 * 1024L,
) {

    fun download(entry: ModelEntry): Flow<DownloadProgress> = flow {
        emit(DownloadProgress.Starting(entry))

        val partFile = store.partFileFor(entry)
        val finalFile = store.fileFor(entry)

        // Fresh attempt: discard any prior partial.
        partFile.delete()
        partFile.parentFile?.mkdirs()

        val request = Request.Builder().url(entry.url).get().build()

        val response = try {
            client.newCall(request).execute()
        } catch (io: IOException) {
            emit(DownloadProgress.Failed(entry, "Network error: ${io.message}", io))
            return@flow
        }

        response.use { resp ->
            if (!resp.isSuccessful) {
                emit(DownloadProgress.Failed(entry, "HTTP ${resp.code}"))
                return@flow
            }
            val body = resp.body
            if (body == null) {
                emit(DownloadProgress.Failed(entry, "Empty response body"))
                return@flow
            }

            val advertised = body.contentLength().takeIf { it > 0 } ?: entry.sizeBytes
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            var lastEmit = 0L

            try {
                body.byteStream().use { input ->
                    partFile.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            written += n
                            if (written - lastEmit >= emitEveryBytes) {
                                lastEmit = written
                                emit(
                                    DownloadProgress.Running(
                                        entry = entry,
                                        bytesRead = written,
                                        totalBytes = advertised,
                                        fraction = if (advertised > 0)
                                            (written.toDouble() / advertised).toFloat()
                                                .coerceIn(0f, 1f)
                                        else 0f,
                                    ),
                                )
                            }
                        }
                    }
                }
            } catch (io: IOException) {
                partFile.delete()
                emit(DownloadProgress.Failed(entry, "Write error: ${io.message}", io))
                return@flow
            }

            // Final progress beat.
            emit(
                DownloadProgress.Running(
                    entry = entry,
                    bytesRead = written,
                    totalBytes = advertised,
                    fraction = 1f,
                ),
            )

            // Length sanity (~5% per IMPLEMENTATION_NOTES §2).
            val expected = entry.sizeBytes
            if (written !in (expected - expected / 20)..(expected + expected / 20)) {
                partFile.delete()
                emit(
                    DownloadProgress.Failed(
                        entry,
                        "Size mismatch: got=$written expected=$expected",
                    ),
                )
                return@flow
            }

            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            if (actualHash != entry.sha256) {
                partFile.delete()
                emit(
                    DownloadProgress.Failed(
                        entry,
                        "SHA-256 mismatch: got=$actualHash expected=${entry.sha256}",
                    ),
                )
                return@flow
            }

            // Atomic rename. If finalFile already exists from a stale run, replace it.
            finalFile.delete()
            if (!partFile.renameTo(finalFile)) {
                partFile.delete()
                emit(DownloadProgress.Failed(entry, "Failed to rename .part to final"))
                return@flow
            }

            emit(DownloadProgress.Completed(entry, finalFile))
        }
    }.flowOn(Dispatchers.IO)
}
