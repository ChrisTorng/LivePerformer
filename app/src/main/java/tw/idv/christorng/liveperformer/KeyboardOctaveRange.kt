package tw.idv.christorng.liveperformer

/** Align the visible keyboard to musical C boundaries instead of starting at B. */
object KeyboardOctaveRange {
    fun floorC(note: Int): Int = (note.coerceIn(0, 127) / 12) * 12
    fun ceilC(note: Int): Int = (((note.coerceIn(0, 127) + 11) / 12) * 12).coerceAtMost(127)

    /** A playable keyboard covers two complete octaves (C to C). */
    fun playBase(recorded: IntRange?, manualBase: Int, bassOrDrums: Boolean): Int {
        val defaultBase = if (bassOrDrums) 36 else 48 // C2 / C3
        return floorC(if (manualBase >= 0) manualBase else recorded?.first ?: defaultBase)
            .coerceAtMost(96) // Leave room for C + two octaves.
    }

    /** A monitor expands to all recorded/held pitches with whole-octave boundaries. */
    fun monitorBounds(low: Int?, high: Int?, playBase: Int): IntRange {
        if (low == null || high == null) {
            val start = floorC(playBase).coerceAtMost(96)
            return start..(start + 24)
        }
        val start = floorC(low)
        val end = maxOf(ceilC(high), start + 12).coerceAtMost(127)
        return start..end
    }
}
