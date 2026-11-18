package tw.idv.christorng.liveperformer

import org.junit.Assert.*
import org.junit.Test

class DrumPadsTest {
    @Test fun fixedMpkPadOrderMatchesUserRolesAndGmNumbers() {
        assertEquals(listOf(
            "bassDrum", "snare", "closedHiHat", "openHiHat",
            "lowTom", "highTom", "ride", "crash"
        ), DrumPads.keys)
        assertEquals(listOf(36, 38, 42, 46, 45, 50, 51, 49),
            (36..43).map { DrumPads.canonicalForPad(it) })
    }

    @Test fun drumPadActionWorksForBothNoteAndMomentaryCcButNotOtherChannels() {
        val pads = MidiRoute(1, "mpk", 1, "note", 36, 43, 1, "drum-pads")
        val press = MidiEvent("mpk", "MPKmini2", 1, 0, 0x91, 39, 99)
        val release = press.copy(status = 0x81, data2 = 0)
        assertEquals(46 to 99, pads.noteFor(press))
        assertEquals(46 to 0, pads.noteFor(release))
        assertFalse(pads.matches(press.copy(status = 0x90)))
        val cc = MidiRoute(2, "mpk", 1, "cc", 20, 27, 1, "drum-pads")
        assertEquals(42 to 100, cc.noteFor(press.copy(status = 0xb1, data1 = 22, data2 = 127)))
        assertEquals(42 to 0, cc.noteFor(press.copy(status = 0xb1, data1 = 22, data2 = 0)))
    }

    @Test fun customSoundFontRemapDoesNotChangeCanonicalGmOrUnrelatedNotes() {
        val map = DrumMap("Custom", listOf(60, 61, 62, 63, 64, 65, 66, 67))
        assertEquals(62, map.outputNote(42)) // Closed hi-hat
        assertEquals(63, map.outputNote(46)) // Open hi-hat
        assertEquals(36, DrumMap.GM.outputNote(36))
        assertEquals(70, map.outputNote(70)) // Unknown drum notes pass through
        assertEquals(42, DrumPads.canonicalForPad(38))
    }

    @Test fun invalidHiHatOverlappingTargetNotesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            DrumMap("No choke possible", listOf(36, 38, 42, 42, 45, 50, 51, 49))
        }
    }
}
