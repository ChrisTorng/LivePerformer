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
            assertTrue(controller.applyMpkMiniMk2ControlsPreset(key, firstKnobCc = 70))
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
                (70..77).map { number ->
                    cc.single { it.first == number && it.channel == 0 }.action
                })
            assertTrue(cc.filter { it.channel == 1 }.all { it.ccMode == "press-toggle" })
            // Switching hardware CC profile never corrupts Note bank routing.
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
