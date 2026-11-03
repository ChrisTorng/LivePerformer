package tw.idv.christorng.liveperformer

import kotlin.math.floor

/** PPQ-based transport math shared by MIDI capture and playback. */
object LoopMath {
    const val PPQ = 960

    fun beatTicks(denominator: Int): Int = PPQ * 4 / denominator

    fun barTicks(numerator: Int, denominator: Int): Int =
        numerator * beatTicks(denominator)

    fun loopTicks(bars: Int, numerator: Int = 4, denominator: Int = 4): Int =
        bars.coerceIn(1, 8) * barTicks(numerator, denominator)

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
