package tw.idv.christorng.liveperformer

import kotlin.math.floor

/** PPQ-based transport math shared by MIDI capture and playback. */
object LoopMath {
    const val PPQ = 960

    fun loopTicks(bars: Int): Int = bars.coerceIn(1, 8) * 4 * PPQ

    fun nextBoundary(position: Double, length: Int): Double {
        val cycle = floor(position / length)
        return if (position - cycle * length < 0.001) cycle * length else (cycle + 1) * length
    }

    /** Returns the next absolute occurrence strictly after [previous]. */
    fun nextOccurrence(previous: Double, eventTick: Int, length: Int): Double =
        (floor((previous - eventTick) / length) + 1.0) * length + eventTick

    fun recordedTick(absolute: Double, start: Double, length: Int): Int =
        (absolute - start).toInt().coerceIn(0, length - 1)
}
