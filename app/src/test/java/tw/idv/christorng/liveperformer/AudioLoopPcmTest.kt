package tw.idv.christorng.liveperformer

import org.junit.Assert.*
import org.junit.Test

class AudioLoopPcmTest {
    @Test fun silenceAndFullScalePeaks() {
        assertTrue(LoopPcm.peaks(ShortArray(0)).isEmpty())
        val samples = ShortArray(128)
        samples[64] = Short.MIN_VALUE
        val peaks = LoopPcm.peaks(samples)
        assertEquals(128, peaks.size)
        assertEquals(1f, peaks[64], 0f)
        assertEquals(0f, peaks[0], 0f)
    }
}
