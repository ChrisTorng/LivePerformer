package tw.idv.christorng.liveperformer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MpkControlsAndroidTest {
    @Test fun presetCreatesCorrectIndependentNoteBanksCcBanksAndKnobs() {
        val controller = LoopController(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            controller.newProject(withTemplateTracks = true)
            val key = "mpk-controls-test"
            assertTrue(controller.applyMpkMiniMk2Preset(key))
            assertTrue(controller.applyMpkMiniMk2ControlsPreset(key)) // Editor screenshot: K1..K8 = CC 2..9
            val routes = controller.state.value.midiRoutes
            val notes = routes.filter { it.kind == "note" && it.deviceKey == key }
            assertEquals(listOf("drum-pads", "scale"), notes.sortedBy { it.first }.map { it.action })
            assertEquals(36, notes.first { it.first == 44 }.noteBase)
            assertEquals(24, routes.count { it.kind == "cc" && it.deviceKey == key })
            val cc = routes.filter { it.kind == "cc" }
            val expectedA = listOf("play-stop", "record-toggle", "metronome-toggle") +
                List(5) { "slot-solo" }
            val expectedB = List(7) { "slot-mute" } + "mute-all"
            assertEquals(expectedA, (20..27).map { number ->
                cc.single { it.first == number && it.channel == 1 }.action
            })
            assertEquals(expectedB, (28..35).map { number ->
                cc.single { it.first == number && it.channel == 1 }.action
            })
            assertEquals(List(7) { "slot-volume" } + "master",
                (2..9).map { number ->
                    cc.single { it.first == number && it.channel == 0 }.action
                })
            assertTrue(cc.filter { it.channel == 1 && it.first in 20..27 }
                .all { it.ccMode == "press-toggle" }) // Physical Bank A = Momentary
            assertTrue(cc.filter { it.channel == 1 && it.first in 28..35 }
                .all { it.ccMode == "absolute" }) // Physical Bank B = Toggle
            assertEquals(2, controller.defaultKnobFirstCc(key))
            val joystickUp = MidiEvent(key, "MPKmini2", 1, 0, 0xb0, 1, 95)
            val joystickDown = joystickUp.copy(data1 = 11)
            val pitchBend = joystickUp.copy(status = 0xe0, data1 = 0, data2 = 64)
            assertFalse(cc.any { it.matches(joystickUp) }) // CC1 Modulation
            assertFalse(cc.any { it.matches(joystickDown) }) // CC11 Expression
            assertFalse(cc.any { it.matches(pitchBend) })
            // Switching hardware knob profile never corrupts Note bank routing.
            assertTrue(controller.applyMpkMiniMk2ControlsPreset(key, 70))
            assertEquals(24, controller.state.value.midiRoutes.count {
                it.kind == "cc" && it.deviceKey == key
            })
        } finally {
            controller.newProject(false)
            controller.close()
        }
    }
}
