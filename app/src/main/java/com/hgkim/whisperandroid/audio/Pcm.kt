package com.hgkim.whisperandroid.audio

import kotlin.math.min
import kotlin.math.sqrt

/**
 * PCM helpers — pure functions, no Android dependencies. Tested in isolation.
 */
object Pcm {

    /** Sample rate the entire pipeline runs at. Whisper expects 16 kHz mono. */
    const val SAMPLE_RATE_HZ = 16_000

    /** Hard ceiling on a single utterance: 60 s × 16 kHz = 960 000 samples. */
    const val MAX_RECORDING_SECONDS = 60
    const val MAX_RECORDING_SAMPLES = SAMPLE_RATE_HZ * MAX_RECORDING_SECONDS

    /** Recommended capture chunk: 50 ms = 800 samples at 16 kHz. */
    const val CHUNK_MS = 50
    const val CHUNK_SAMPLES = SAMPLE_RATE_HZ * CHUNK_MS / 1000   // 800

    /**
     * Convert signed 16-bit PCM to `Float` samples in `[-1, 1]`.
     *
     * `Short.MIN_VALUE` is divided by `32768` (not `32767`) so that the most
     * negative input maps to exactly `-1.0f`, matching whisper.cpp's
     * expectations.
     */
    fun normalize(samples: ShortArray, length: Int = samples.size): FloatArray {
        val n = min(length, samples.size).coerceAtLeast(0)
        val out = FloatArray(n)
        for (i in 0 until n) {
            out[i] = samples[i] / 32768.0f
        }
        return out
    }

    /**
     * Root-mean-square of a chunk, normalised to `[0, 1]` for level-meter UI.
     *
     * Empty / null-length input returns `0f`. Internally promotes each sample
     * to `Long` so a fully-saturated chunk (`Short.MIN_VALUE`²) cannot
     * overflow.
     */
    fun rmsOf(samples: ShortArray, length: Int = samples.size): Float {
        val n = min(length, samples.size)
        if (n <= 0) return 0f
        var sumSq = 0.0
        for (i in 0 until n) {
            val s = samples[i].toInt()
            sumSq += (s.toLong() * s.toLong()).toDouble()
        }
        val rms = sqrt(sumSq / n)
        return (rms / 32768.0).toFloat().coerceIn(0f, 1f)
    }
}
