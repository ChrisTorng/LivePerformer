package tw.idv.christorng.liveperformer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TapMultiRouteAndroidTest {
    private fun controller() = LoopController(
        InstrumentationRegistry.getInstrumentation().targetContext)

    @Test fun tapArmedRecordingStartsOnFourthTapWhileNormalPlayUsesCountIn() {
        val loop = controller()
        try {
            loop.newProject(true)
            assertTrue(loop.state.value.countInEnabled)
            loop.recordPause()
            assertTrue(loop.state.value.armed)
            assertFalse(loop.state.value.playing)
            listOf(1_000L, 1_500L, 2_000L, 2_500L).forEach { loop.tapTempo(it) }
            assertEquals(120, loop.state.value.bpm)
            assertTrue(loop.state.value.recording)
            assertEquals(0, loop.state.value.countInBeatsLeft)
            loop.recordPause()
            loop.playStop()
            loop.recordPause()
            assertTrue(loop.state.value.armed)
            assertFalse(loop.state.value.playing)
            loop.playStop()
            Thread.sleep(35)
            assertTrue(loop.state.value.countInBeatsLeft > 0)
        } finally {
            loop.newProject(false)
            loop.close()
        }
    }

    @Test fun continuousReplaceKeepsOldTakeThroughSilentPasses() {
        val loop = controller()
        try {
            loop.newProject(true)
            loop.setCountInEnabled(false)
            loop.setBars(1)
            loop.setBpm(240) // A four-beat loop lasts one second.
            val piano = loop.state.value.tracks.first { it.name == "鋼琴" }
            loop.recordPause()
            assertTrue(loop.state.value.armed)
            assertFalse(loop.state.value.playing)
            loop.playStop()
            Thread.sleep(25)
            assertTrue(loop.state.value.recording)
            loop.onMidi(NativeEngine.NOTE_ON, 0, 60, 100)
            loop.onMidi(NativeEngine.NOTE_OFF, 0, 60, 0)
            Thread.sleep(1150)
            assertTrue("recording must not auto-stop after one loop", loop.state.value.recording)
            assertEquals(1, loop.state.value.tracks.first { it.id == piano.id }.notes)
            // Empty next pass retains old loop notes and keeps playing.
            Thread.sleep(1050)
            assertTrue(loop.state.value.recording)
            assertEquals(1, loop.state.value.tracks.first { it.id == piano.id }.notes)
            loop.onMidi(NativeEngine.NOTE_ON, 0, 64, 100)
            loop.onMidi(NativeEngine.NOTE_OFF, 0, 64, 0)
            assertEquals(1, loop.state.value.tracks.first { it.id == piano.id }.notes)
            loop.recordPause()
            assertFalse(loop.state.value.recording)
        } finally {
            loop.newProject(false)
            loop.close()
        }
    }

    @Test fun chosenTargetsAreRecordedIndependentlyWhileExplicitMpkPadRemainsFixed() {
        val loop = controller()
        try {
            loop.newProject(true)
            val piano = loop.state.value.tracks.first { it.name == "鋼琴" }
            val bass = loop.state.value.tracks.first { it.name == "貝斯" }
            val drum = loop.state.value.tracks.first { it.name == "鼓組" }
            assertEquals(setOf(piano.id), loop.state.value.inputTargetIds)
            loop.toggleInputTarget(bass.id)
            assertEquals(setOf(piano.id, bass.id), loop.state.value.inputTargetIds)
            loop.setCountInEnabled(false)
            loop.recordPause()
            loop.onMidi(NativeEngine.NOTE_ON, 0, 64, 111)
            assertEquals(111, loop.state.value.keyboardVelocities[64])
            loop.onMidi(NativeEngine.NOTE_OFF, 0, 64, 0)
            assertFalse(loop.state.value.keyboardVelocities.containsKey(64))
            loop.recordPause()
            val views = loop.state.value.tracks
            assertEquals(1, views.first { it.id == piano.id }.notes)
            assertEquals(1, views.first { it.id == bass.id }.notes)
            assertEquals(0, views.first { it.id == drum.id }.notes)

            assertTrue(loop.applyMpkMiniMk2Preset("mpk-test"))
            loop.onExternalMidi(MidiEvent("mpk-test", "MPKmini2", 1, 1, 0x91, 36, 88))
            assertTrue(loop.state.value.tracks.first { it.id == drum.id }.activityLevel > 0f)
            assertEquals(setOf(piano.id, bass.id), loop.state.value.inputTargetIds)
        } finally {
            loop.newProject(false)
            loop.close()
        }
    }

    @Test fun independentRouteJsonIsAtomicAndLeavesNotesUnchanged() {
        val loop = controller()
        try {
            loop.newProject(true)
            assertTrue(loop.addMidiRoute("*", 0, "cc", 64, 64, 0, "tap-tempo"))
            val original = loop.exportMidiRouting()
            assertTrue(original.contains("liveperformer.midi-routes.v1"))
            loop.clearMidiRoutes()
            assertTrue(loop.state.value.midiRoutes.isEmpty())
            loop.importMidiRouting(original)
            assertEquals("tap-tempo", loop.state.value.midiRoutes.single().action)
            val before = loop.exportDocument(false)
            val invalid = """{"format":"liveperformer.midi-routes.v1","midiRoutes":[{"id":1,"deviceKey":"*","kind":"cc","channel":99,"first":64,"last":64,"action":"tap-tempo"}]}"""
            try {
                loop.importMidiRouting(invalid)
                fail("invalid JSON must not replace the current MIDI rules")
            } catch (_: IllegalArgumentException) {
                assertEquals("tap-tempo", loop.state.value.midiRoutes.single().action)
                assertEquals(before, loop.exportDocument(false))
            }
        } finally {
            loop.newProject(false)
            loop.close()
        }
    }
}
