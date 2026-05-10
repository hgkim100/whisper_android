package com.hgkim.whisperandroid.model

import android.content.Context
import org.json.JSONObject
import java.io.IOException

/**
 * In-memory representation of `assets/models.json`.
 *
 * Schema version 1 — see docs/IMPLEMENTATION_NOTES.md §2. Phase 1 of the app
 * ships exactly one model (`ggml-tiny.en`); the manifest is parsed for forward
 * compatibility, but consumers should use [defaultModel] for normal lookups.
 */
data class ModelManifest(
    val schemaVersion: Int,
    val defaultModelId: String,
    val models: List<ModelEntry>,
) {

    /** The single English-only model the app actually consumes (Phase 1 §A). */
    val defaultModel: ModelEntry
        get() = models.firstOrNull { it.id == defaultModelId }
            ?: error("Manifest default_model_id='$defaultModelId' not present in models[]")

    companion object {
        const val ASSET_PATH = "models.json"
        const val SUPPORTED_SCHEMA_VERSION = 1

        /** Load and parse `assets/models.json`. Throws [IOException] / [IllegalStateException]. */
        fun load(context: Context): ModelManifest {
            val raw = context.assets.open(ASSET_PATH).use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readText()
            }
            return parse(raw)
        }

        /** Parse a manifest JSON string. Public for tests. */
        fun parse(json: String): ModelManifest {
            val root = JSONObject(json)
            val version = root.getInt("schema_version")
            check(version == SUPPORTED_SCHEMA_VERSION) {
                "Unsupported manifest schema_version=$version (supported=$SUPPORTED_SCHEMA_VERSION)"
            }
            val defaultId = root.getString("default_model_id")
            val arr = root.getJSONArray("models")
            check(arr.length() >= 1) { "Manifest models[] must contain at least one entry" }

            val entries = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ModelEntry(
                    id = o.getString("id"),
                    filename = o.getString("filename"),
                    url = o.getString("url"),
                    sizeBytes = o.getLong("size_bytes"),
                    sha256 = o.getString("sha256").lowercase(),
                ).also { entry ->
                    require(entry.url.startsWith("https://")) {
                        "Manifest entry '${entry.id}' must use HTTPS url"
                    }
                    require(entry.sha256.matches(Regex("^[0-9a-f]{64}$"))) {
                        "Manifest entry '${entry.id}' sha256 must be 64-char lowercase hex"
                    }
                    require(entry.sizeBytes > 0) {
                        "Manifest entry '${entry.id}' size_bytes must be positive"
                    }
                }
            }
            return ModelManifest(version, defaultId, entries)
        }
    }
}

/** A single ggml model artefact described in the manifest. */
data class ModelEntry(
    val id: String,
    val filename: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
)
