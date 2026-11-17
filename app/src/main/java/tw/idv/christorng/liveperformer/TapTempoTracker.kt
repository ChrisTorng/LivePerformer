package tw.idv.christorng.liveperformer

/**
 * Four taps = three elapsed-time intervals. Monotonic timestamps come from
 * SystemClock.elapsedRealtime() in the caller, never from wall-clock time.
 */
class TapTempoTracker {
    private val taps = ArrayList<Long>(4)
    val tapCount: Int get() = taps.size

    fun reset() = taps.clear()

    /** Returns a BPM on the fourth tap, or null while collecting. */
    fun tap(elapsedMillis: Long): Int? {
        if (taps.isNotEmpty()) {
            val delta = elapsedMillis - taps.last()
            if (delta <= 0 || delta > 2_000L) taps.clear()
        }
        taps += elapsedMillis
        if (taps.size < 4) return null
        val spans = taps.zipWithNext { a, b -> b - a }
        taps.clear()
        // Reject grossly uneven tapping rather than changing tempo accidentally.
        if (spans.maxOrNull()!! > spans.minOrNull()!! * 2) return null
        val mean = spans.average()
        return (60_000.0 / mean).toInt().coerceIn(40, 240)
    }
}
