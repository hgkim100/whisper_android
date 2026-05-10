package com.hgkim.whisperandroid.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pure-JVM tests for [ModelManifest.parse].
 *
 * Covers:
 *  - happy path (the shipping `assets/models.json` shape).
 *  - schema_version rejection (forward-incompatible bumps must fail loud).
 *  - http (non-https) URL rejection.
 *  - sha256 format rejection (uppercase / wrong length / non-hex).
 *  - non-positive size_bytes rejection.
 *  - empty / missing models[] rejection.
 *  - lowercase normalisation of sha256 input.
 *  - default_model_id resolution + missing-id failure.
 */
class ModelManifestTest {

    private val validSha = "a".repeat(64)

    private fun manifestJson(
        version: Int = 1,
        defaultId: String = "ggml-tiny.en",
        models: String = """
            [
              {
                "id": "ggml-tiny.en",
                "filename": "ggml-tiny.en.bin",
                "url": "https://example.com/ggml-tiny.en.bin",
                "size_bytes": 77704715,
                "sha256": "$validSha"
              }
            ]
        """.trimIndent(),
    ) = """
        {
          "schema_version": $version,
          "default_model_id": "$defaultId",
          "models": $models
        }
    """.trimIndent()

    @Test
    fun `parse - happy path returns one entry with normalised sha256`() {
        val m = ModelManifest.parse(manifestJson(models = """
            [
              {
                "id": "ggml-tiny.en",
                "filename": "ggml-tiny.en.bin",
                "url": "https://example.com/ggml-tiny.en.bin",
                "size_bytes": 77704715,
                "sha256": "${"A".repeat(64)}"
              }
            ]
        """.trimIndent()))
        assertEquals(1, m.schemaVersion)
        assertEquals("ggml-tiny.en", m.defaultModelId)
        assertEquals(1, m.models.size)
        val entry = m.models.first()
        assertEquals("ggml-tiny.en", entry.id)
        assertEquals(77704715L, entry.sizeBytes)
        // input was uppercase, parser must lowercase it
        assertEquals("a".repeat(64), entry.sha256)
        assertEquals(entry, m.defaultModel)
    }

    @Test
    fun `parse - rejects schema_version 2`() {
        try {
            ModelManifest.parse(manifestJson(version = 2))
            fail("expected IllegalStateException for schema_version=2")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("schema_version=2"))
        }
    }

    @Test
    fun `parse - rejects http (non-https) url`() {
        try {
            ModelManifest.parse(manifestJson(models = """
                [
                  {
                    "id": "x",
                    "filename": "x.bin",
                    "url": "http://example.com/x.bin",
                    "size_bytes": 1,
                    "sha256": "$validSha"
                  }
                ]
            """.trimIndent()))
            fail("expected IllegalArgumentException for non-https url")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("HTTPS"))
        }
    }

    @Test
    fun `parse - rejects sha256 not 64 hex chars`() {
        try {
            ModelManifest.parse(manifestJson(models = """
                [
                  {
                    "id": "x",
                    "filename": "x.bin",
                    "url": "https://example.com/x.bin",
                    "size_bytes": 1,
                    "sha256": "${"a".repeat(63)}"
                  }
                ]
            """.trimIndent()))
            fail("expected IllegalArgumentException for short sha256")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("sha256"))
        }
    }

    @Test
    fun `parse - rejects non-hex sha256`() {
        try {
            ModelManifest.parse(manifestJson(models = """
                [
                  {
                    "id": "x",
                    "filename": "x.bin",
                    "url": "https://example.com/x.bin",
                    "size_bytes": 1,
                    "sha256": "${"z".repeat(64)}"
                  }
                ]
            """.trimIndent()))
            fail("expected IllegalArgumentException for non-hex sha256")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("sha256"))
        }
    }

    @Test
    fun `parse - rejects size_bytes 0`() {
        try {
            ModelManifest.parse(manifestJson(models = """
                [
                  {
                    "id": "x",
                    "filename": "x.bin",
                    "url": "https://example.com/x.bin",
                    "size_bytes": 0,
                    "sha256": "$validSha"
                  }
                ]
            """.trimIndent()))
            fail("expected IllegalArgumentException for size_bytes=0")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("size_bytes"))
        }
    }

    @Test
    fun `parse - rejects empty models array`() {
        try {
            ModelManifest.parse(manifestJson(models = "[]"))
            fail("expected IllegalStateException for empty models")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("models"))
        }
    }

    @Test
    fun `defaultModel - throws when default_model_id missing from models`() {
        val m = ModelManifest.parse(
            manifestJson(defaultId = "does-not-exist"),
        )
        try {
            m.defaultModel
            fail("expected IllegalStateException for unknown default_model_id")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("does-not-exist"))
        }
    }
}
