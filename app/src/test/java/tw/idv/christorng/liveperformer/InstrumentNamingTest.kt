package tw.idv.christorng.liveperformer

import org.junit.Assert.assertEquals
import org.junit.Test

class InstrumentNamingTest {
    @Test fun genericPresetFallsBackToImportedFileName() {
        assertEquals("Salamander Grand Piano",
            InstrumentNaming.bestName("Instrument", "Salamander_Grand_Piano.sf2"))
        assertEquals("鋼琴",
            InstrumentNaming.category("Salamander Grand Piano", 0, "Instrument"))
    }

    @Test fun namedPresetsWinAndGmFallbackStillWorks() {
        assertEquals("Concert Grand", InstrumentNaming.bestName("Concert Grand", "instrument"))
        assertEquals("貝斯", InstrumentNaming.category("Unknown", 33, "Preset"))
        assertEquals("弦樂", InstrumentNaming.category("Unknown", 48, "Solo Violin"))
    }
}
