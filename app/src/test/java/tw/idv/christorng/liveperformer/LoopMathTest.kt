package tw.idv.christorng.liveperformer

import org.junit.Assert.*
import org.junit.Test

class LoopMathTest {
    @Test fun defaultsAreTwoBars() {
        assertEquals(7680, LoopMath.loopTicks(2))
    }
    @Test fun commonMetersHaveCorrectCycleLength() {
        assertEquals(3840, LoopMath.loopTicks(2, 2, 4))
        assertEquals(5760, LoopMath.loopTicks(2, 3, 4))
        assertEquals(7680, LoopMath.loopTicks(2, 4, 4))
        assertEquals(5760, LoopMath.loopTicks(2, 6, 8))
        assertEquals(480, LoopMath.beatTicks(8))
        assertEquals(960, LoopMath.beatTicks(4))
    }
    @Test fun quantizeRecordingToNextCycle() {
        assertEquals(7680.0, LoopMath.nextBoundary(1.0, 7680), 0.001)
        assertEquals(0.0, LoopMath.nextBoundary(0.0, 7680), 0.001)
    }
    @Test fun wrapPlaybackEvents() {
        assertEquals(7680.0, LoopMath.nextOccurrence(7679.0, 0, 7680), 0.001)
        assertEquals(960.0, LoopMath.nextOccurrence(0.0, 960, 7680), 0.001)
        assertEquals(0.0, LoopMath.nextOccurrence(-0.01, 0, 7680), 0.001)
    }
    @Test fun earlyDownbeatIsRetainedBySixteenthNoteGrid() {
        val length = LoopMath.loopTicks(2)
        assertEquals(240, LoopMath.quantizeStep(16))
        assertEquals(120, LoopMath.preRollTicks(16))
        assertEquals(0, LoopMath.quantize(-110.0, 16, length))
        assertEquals(0, LoopMath.quantize(110.0, 16, length))
        assertEquals(240, LoopMath.quantize(130.0, 16, length))
        assertEquals(0, LoopMath.quantize(length - 80.0, 16, length, wrap = true))
        assertEquals(length - 1, LoopMath.quantize(length - 10.0, 16, length))
    }

    @Test fun supportedQuantizationsProduceCorrectStep() {
        assertEquals(960, LoopMath.quantizeStep(4))
        assertEquals(480, LoopMath.quantizeStep(8))
        assertEquals(240, LoopMath.quantizeStep(16))
        assertEquals(120, LoopMath.quantizeStep(32))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedQuantizationIsRejected() {
        LoopMath.quantizeStep(13)
    }

    @Test fun endOfTakeNeverLeaksIntoNextBar() {
        assertEquals(7679, LoopMath.recordedTick(8000.0, 0.0, 7680))
    }
}
