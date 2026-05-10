package com.hgkim.whisperandroid.model

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Filesystem-side bookkeeping for downloaded ggml model files.
 *
 * Layout (under [Context.getFilesDir]):
 * ```
 * <filesDir>/
 *   models/
 *     ggml-tiny.en.bin        ← finalised model, ready for WhisperEngine
 *     ggml-tiny.en.bin.part   ← in-flight download (rename-on-complete)
 * ```
 *
 * Filesystem operations are blocking — callers should hop off the main thread
 * (typically `Dispatchers.IO`).
 */
class ModelStore(private val context: Context) {

    private val rootDir: File by lazy {
        File(context.filesDir, MODELS_DIR).apply { mkdirs() }
    }

    /** Final on-disk path for a model entry (may not yet exist). */
    fun fileFor(entry: ModelEntry): File = File(rootDir, entry.filename)

    /** Sibling `.part` path used during download. */
    fun partFileFor(entry: ModelEntry): File = File(rootDir, entry.filename + PART_SUFFIX)

    /**
     * Returns true iff the finalised file exists with the manifest-declared
     * length. Hash verification is not re-run on every check (too slow); call
     * [verifySha256] explicitly when paranoia is warranted.
     */
    fun isModelReady(entry: ModelEntry): Boolean {
        val f = fileFor(entry)
        return f.isFile && f.length() == entry.sizeBytes
    }

    /** Delete both the finalised file and any leftover `.part`. */
    fun delete(entry: ModelEntry) {
        fileFor(entry).delete()
        partFileFor(entry).delete()
    }

    /**
     * Compute SHA-256 over [file] and compare against [entry]. Reads in 64KB
     * chunks; safe for ~75 MB tiny.en. Returns true on match.
     */
    fun verifySha256(entry: ModelEntry, file: File = fileFor(entry)): Boolean {
        if (!file.isFile) return false
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        return hex == entry.sha256
    }

    companion object {
        const val MODELS_DIR = "models"
        const val PART_SUFFIX = ".part"
    }
}
