package tw.idv.christorng.liveperformer

import org.junit.Assert.*
import org.junit.Test

class TapTempoTrackerTest {
    @Test fun fourBeatsUseMeanOfThreeIntervals() {
        val taps = TapTempoTracker()
        assertNull(taps.tap(1000))
        assertNull(taps.tap(1500))
        assertNull(taps.tap(2010))
        assertEquals(120, taps.tap(2500))
        assertEquals(0, taps.tapCount)
    }

    @Test fun staleIntervalsRestartCollection() {
        val taps = TapTempoTracker()
        assertNull(taps.tap(1000))
        assertNull(taps.tap(4001))
        assertEquals(1, taps.tapCount)
        assertNull(taps.tap(4501))
        assertNull(taps.tap(5001))
        assertEquals(120, taps.tap(5501))
    }

    @Test fun unstableOrOutOfRangeTapsAreHandled() {
        val taps = TapTempoTracker()
        listOf(1000L, 1400L, 1800L, 3000L).forEach { taps.tap(it) }
        assertEquals(0, taps.tapCount)
        listOf(4000L, 4300L, 4600L).forEach { taps.tap(it) }
        assertEquals(200, taps.tap(4900))
    }
}
