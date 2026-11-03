package tw.idv.christorng.liveperformer

import kotlin.math.floor
import kotlin.math.roundToInt

/** Musical timing uses 960 ticks per quarter note; quantization never delays live audio. */
object LoopMath {
    const val PPQ = 960
    val supportedQuantization = listOf(4, 8, 16, 32)

    fun beatTicks(denominator: Int): Int = PPQ * 4 / denominator
    fun barTicks(numerator: Int, denominator: Int): Int = numerator * beatTicks(denominator)
    fun loopTicks(bars: Int, numerator: Int = 4, denominator: Int = 4): Int =
        bars.coerceIn(1, 8) * barTicks(numerator, denominator)

    fun quantizeStep(division: Int): Int {
        require(division in supportedQuantization) { "Unsupported quantization grid" }
        return PPQ * 4 / division
    }

    /** Half a subdivision before the downbeat may legitimately round to tick zero. */
    fun preRollTicks(division: Int): Int = quantizeStep(division) / 2

    fun quantize(position: Double, division: Int, length: Int, wrap: Boolean = false): Int {
        require(length > 0)
        val nearest = (position / quantizeStep(division)).roundToInt() * quantizeStep(division)
        return if (wrap && nearest >= length) 0 else nearest.coerceIn(0, length - 1)
    }

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
