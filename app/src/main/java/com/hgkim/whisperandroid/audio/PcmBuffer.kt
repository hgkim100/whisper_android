package com.hgkim.whisperandroid.audio

/**
 * Auto-growing accumulator for 16-bit PCM samples captured during one
 * recording session.
 *
 * - Starts at [initialCapacity] samples and doubles as needed.
 * - Hard ceiling at [maxSamples] (default = 60 s @ 16 kHz). Once reached,
 *   [append] returns `false` and the buffer truncates the call's chunk to
 *   keep the size at exactly [maxSamples] — preserving any prefix that fit.
 *
 * Not thread-safe; intended to be owned by a single capture coroutine.
 */
class PcmBuffer(
    private val maxSamples: Int = Pcm.MAX_RECORDING_SAMPLES,
    initialCapacity: Int = Pcm.SAMPLE_RATE_HZ,   // 1 s
) {

    private var data: ShortArray = ShortArray(initialCapacity.coerceAtLeast(0))
    /** Number of valid samples currently held. */
    var size: Int = 0
        private set

    /** True once the buffer has reached its hard ceiling. */
    val isFull: Boolean get() = size >= maxSamples

    /**
     * Copy [length] samples (defaults to [chunk]'s entire size) into the buffer.
     *
     * @return `true` if the entire chunk fit, `false` if some / all of the
     *         chunk was dropped because the buffer hit [maxSamples].
     */
    fun append(chunk: ShortArray, length: Int = chunk.size): Boolean {
        val available = maxSamples - size
        if (available <= 0) return false

        val toCopy = if (length <= available) length else available
        ensureCapacity(size + toCopy)
        System.arraycopy(chunk, 0, data, size, toCopy)
        size += toCopy
        return toCopy == length
    }

    /** Snapshot the buffer as float32 samples in `[-1, 1]`. */
    fun toFloatArray(): FloatArray = Pcm.normalize(data, size)

    /** Reset the buffer for reuse. Capacity is **not** released. */
    fun clear() {
        size = 0
    }

    private fun ensureCapacity(required: Int) {
        if (required <= data.size) return
        var newCap = if (data.isEmpty()) 1 else data.size
        while (newCap < required) newCap *= 2
        if (newCap > maxSamples) newCap = maxSamples
        data = data.copyOf(newCap)
    }
}
