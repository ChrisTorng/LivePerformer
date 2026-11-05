package tw.idv.christorng.liveperformer

import org.junit.Assert.*
import org.junit.Test

class MidiRoutingTest {
    private fun message(status: Int, a: Int, b: Int, key: String = "mpk") =
        MidiEvent(key, "MPKmini2", 1, 0, status, a, b)

    @Test fun differentBanksSameChannelCanPlayDifferentTracks() {
        val a = MidiRoute(1, "mpk", 1, "note", 36, 43, 1, "note")
        val b = MidiRoute(2, "mpk", 1, "note", 44, 51, 2, "note")
        assertTrue(a.matches(message(0x91, 36, 80)))
        assertFalse(b.matches(message(0x91, 36, 80)))
        assertTrue(b.matches(message(0x91, 48, 80)))
        assertEquals(48 to 80, b.noteFor(message(0x91, 48, 80)))
        assertEquals(36 to 0, a.noteFor(message(0x81, 36, 0)))
    }

    @Test fun ccPadsMomentaryCanTriggerNotesAndReleaseThem() {
        val cc = MidiRoute(3, "mpk", 1, "cc", 20, 27, 2, "note", 48)
        assertEquals(48 to 100, cc.noteFor(message(0xb1, 20, 127)))
        assertEquals(55 to 100, cc.noteFor(message(0xb1, 27, 127)))
        assertEquals(48 to 0, cc.noteFor(message(0xb1, 20, 0)))
        assertFalse(cc.matches(message(0xb0, 20, 127)))
        assertFalse(cc.matches(message(0xb1, 5, 127)))
    }

    @Test fun scaleMapEightPadsGivesOctaveAndKeepsDrumPitchFixed() {
        val scale = MidiRoute(4, "*", 1, "note", 44, 51, 2, "scale", 36)
        val notes = (44..51).map { scale.noteFor(message(0x91, it, 91))?.first }
        assertEquals(listOf(36, 38, 40, 41, 43, 45, 47, 48), notes)
        val drum = MidiRoute(5, "*", 1, "note", 36, 43, 1, "note")
        assertEquals(36 to 91, drum.noteFor(message(0x91, 36, 91)))
    }

    @Test fun decoderHandlesFragmentedMessagesRunningStatusAndRealtime() {
        val messages = mutableListOf<Triple<Int, Int, Int>>()
        val decoder = MidiStreamDecoder { status, a, b -> messages += Triple(status, a, b) }
        decoder.accept(byteArrayOf(0x91.toByte(), 36), 0, 2)
        decoder.accept(byteArrayOf(0xf8.toByte(), 100.toByte(), 37, 0), 0, 4)
        decoder.accept(byteArrayOf(0x81.toByte(), 36, 0), 0, 3)
        assertEquals(listOf(
            Triple(0xf8, 0, 0), Triple(0x91, 36, 100),
            Triple(0x91, 37, 0), Triple(0x81, 36, 0)
        ), messages)
    }

    @Test fun naturalMinorAndMajorSupportSelectableKeys() {
        assertEquals(listOf(36, 38, 40, 41, 43, 45, 47, 48),
            (0..7).map { MidiPitch.scale(36, it, "major") })
        assertEquals(listOf(36, 38, 39, 41, 43, 44, 46, 48),
            (0..7).map { MidiPitch.scale(36, it, "minor") })
        val gMajorCc = MidiRoute(6, "mpk", 1, "cc", 20, 27, 2, "scale", 43)
        assertEquals(43 to 100, gMajorCc.noteFor(message(0xb1, 20, 127)))
        assertEquals(55 to 100, gMajorCc.noteFor(message(0xb1, 27, 127)))
        assertEquals(43 to 0, gMajorCc.noteFor(message(0xb1, 20, 0)))
    }

    @Test fun customPadMapOverridesCcNumberAndCanDisableIndividualPad() {
        val custom = MidiRoute(7, "mpk", 1, "cc", 20, 27, 2, "custom",
            noteMap = listOf(36, 38, 40, 41, 43, 45, 47, -1))
        assertEquals(36 to 100, custom.noteFor(message(0xb1, 20, 127)))
        assertEquals(47 to 0, custom.noteFor(message(0xb1, 26, 0)))
        assertNull(custom.noteFor(message(0xb1, 27, 127)))
        assertFalse(custom.matches(message(0xb0, 20, 127)))
        assertEquals("C2", MidiPitch.name(36))
    }
}
