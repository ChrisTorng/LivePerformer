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
    @Test fun endOfTakeNeverLeaksIntoNextBar() {
        assertEquals(7679, LoopMath.recordedTick(8000.0, 0.0, 7680))
    }
}
