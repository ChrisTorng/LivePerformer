package tw.idv.christorng.liveperformer

import org.junit.Assert.*
import org.junit.Test

class AudioCalibrationTest {
    private fun sample(ms: Float, confidence: Float = .9f) =
        "輸出 callback → 麥克風捕捉：$ms ms\n相關性 $confidence"
    @Test fun acceptsStableMajority() {
        assertEquals(22f, PulseLatencyTester.compensationMedian(listOf(sample(20f), sample(22f), sample(24f)))!!, 0f)
    }
    @Test fun rejectsNoisyOrUnstableCalibration() {
        assertNull(PulseLatencyTester.compensationMedian(listOf(sample(20f), sample(100f), sample(120f))))
        assertNull(PulseLatencyTester.compensationMedian(listOf(sample(20f, .2f), sample(21f, .2f), sample(22f))))
    }
}
