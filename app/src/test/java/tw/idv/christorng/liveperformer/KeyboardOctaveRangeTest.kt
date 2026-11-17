package tw.idv.christorng.liveperformer

import org.junit.Assert.*
import org.junit.Test

class KeyboardOctaveRangeTest {
    @Test fun noRecordingsBeginOnCAndCoverTwoOctaves() {
        assertEquals(36, KeyboardOctaveRange.playBase(null, -1, true))
        assertEquals(48, KeyboardOctaveRange.playBase(null, -1, false))
        assertEquals(36..60, KeyboardOctaveRange.monitorBounds(null, null, 36))
        assertEquals(48..72, KeyboardOctaveRange.monitorBounds(null, null, 48))
    }

    @Test fun savedBAndRecordedBDoNotMakePartialOctaves() {
        assertEquals(48, KeyboardOctaveRange.playBase(null, 59, false))
        assertEquals(36, KeyboardOctaveRange.playBase(47..60, -1, true))
        assertEquals(60, KeyboardOctaveRange.playBase(71..91, -1, false))
    }

    @Test fun monitoringRoundOutwardToCompleteOctaves() {
        assertEquals(48..84, KeyboardOctaveRange.monitorBounds(59, 80, 48))
        assertEquals(60..72, KeyboardOctaveRange.monitorBounds(64, 67, 48))
        assertEquals(0..24, KeyboardOctaveRange.monitorBounds(0, 24, 0))
    }
}
