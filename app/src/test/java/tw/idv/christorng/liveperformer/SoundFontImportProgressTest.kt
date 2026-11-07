package tw.idv.christorng.liveperformer

import org.junit.Assert.*
import org.junit.Test

class SoundFontImportProgressTest {
    @Test fun normalCopyReportsActualFraction() {
        val progress = SoundFontImportProgress("複製 SF2", 5_000L, 20_000L)
        assertEquals(0.25f, progress.fraction!!, 0.001f)
    }

    @Test fun sampleCountGivesReliableCountFractionWithoutKnownExpandedSize() {
        val progress = SoundFontImportProgress(
            stage = "SF3 → SF2", completedBytes = 60L * 1024 * 1024,
            completedItems = 676, totalItems = 1246
        )
        assertNotNull(progress.fraction)
        assertEquals(676f / 1246f, progress.fraction!!, 0.0001f)
        assertEquals(60L * 1024 * 1024, progress.completedBytes)
    }

    @Test fun sampleFractionUsesCountRatherThanOutputByteSize() {
        val progress = SoundFontImportProgress("SF3", completedBytes = 10_000,
            totalBytes = 400_000L, completedItems = 1, totalItems = 4)
        assertEquals(0.25f, progress.fraction!!, 0.001f)
    }

    @Test fun unknownArchiveExpansionMustNotInventPercentage() {
        assertNull(SoundFontImportProgress("解壓縮 ZIP", 600_000_000L).fraction)
        assertNull(SoundFontImportProgress("讀取", 1L, 0L).fraction)
    }

    @Test fun fractionRemainsBoundedIfProviderSizeIsInaccurate() {
        assertEquals(1f, SoundFontImportProgress("複製", 110L, 100L).fraction!!, 0.001f)
        assertEquals(0f, SoundFontImportProgress("複製", -1L, 100L).fraction!!, 0.001f)
    }
}
