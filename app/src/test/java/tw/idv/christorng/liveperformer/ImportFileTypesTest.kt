package tw.idv.christorng.liveperformer

import org.junit.Assert.*
import org.junit.Test

class ImportFileTypesTest {
    @Test fun acceptsSupportedSoundFontNames() {
        listOf("Piano.sf2", "Strings.SF3", "bank.zip", "grand.tar.xz",
            "piano.sf2.xz", "organ.sf3.xz").forEach {
            assertTrue(it, ImportFileTypes.isSoundFontFileName(it))
        }
        listOf("track.sfz", "bank.sf4", "audio.mp3", "font.tar", "font.sf3.zip.exe")
            .forEach { assertFalse(it, ImportFileTypes.isSoundFontFileName(it)) }
    }

    @Test fun projectsOnlyAcceptLvpAndLvpt() {
        assertTrue(ImportFileTypes.isProjectFileName("demo.LVP"))
        assertTrue(ImportFileTypes.isProjectFileName("preset.lvpt"))
        assertFalse(ImportFileTypes.isProjectFileName("demo.json"))
        assertFalse(ImportFileTypes.isProjectFileName("demo.lvp.txt"))
    }

    @Test fun archiveEntriesOnlySelectSoundFonts() {
        assertEquals("sf2", ImportFileTypes.soundFontExtension("bank/Piano.SF2"))
        assertEquals("sf3", ImportFileTypes.soundFontExtension("bank/Piano.sf3"))
        assertNull(ImportFileTypes.soundFontExtension("bank/description.txt"))
    }

    @Test fun logsOmitDeviceAndTimestamp() {
        val event = MidiEvent("key", "MPK mini MK2", 12, 0, 0xb0, 20, 127)
        val line = MidiDiagnostics.format(event)
        assertEquals("Ch 1 · CC 20 = 127", line)
        assertFalse(line.contains("MPK"))
        assertFalse(line.contains("#12"))
        assertFalse(line.contains(":"))
    }
}
