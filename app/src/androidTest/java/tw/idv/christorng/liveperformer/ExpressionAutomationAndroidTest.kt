package tw.idv.christorng.liveperformer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExpressionAutomationAndroidTest {
    private fun pianoEvents(controller: LoopController): List<List<Int>> {
        val json = JSONObject(controller.exportDocument(false))
        val tracks = json.getJSONArray("tracks")
        val piano = (0 until tracks.length()).map { tracks.getJSONObject(it) }
            .first { it.getInt("id") == 5 }
        val events = piano.getJSONArray("events")
        return (0 until events.length()).map { i ->
            val event = events.getJSONArray(i)
            (0..3).map { event.getInt(it) }
        }
    }

    private fun joystick(value: Int) =
        MidiEvent("mpk-expression-test", "MPKmini2", 17, 0, 0xb0, 11, value)

    @Test fun expressionOnlyTakePersistsCurveWithoutErasingRecordedPianoNotes() {
        val controller = LoopController(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            controller.newProject(withTemplateTracks = true)
            controller.setCountInEnabled(false)
            controller.recordPause()
            controller.playStop()
            Thread.sleep(30)
            controller.onMidi(NativeEngine.NOTE_ON, 0, 60, 95)
            Thread.sleep(25)
            controller.onMidi(NativeEngine.NOTE_OFF, 0, 60, 0)
            controller.recordPause()
            controller.playStop()
            val originalNotes = pianoEvents(controller).filter { it[1] <= NativeEngine.NOTE_OFF }
            assertTrue(originalNotes.any { it[1] == NativeEngine.NOTE_ON && it[2] == 60 })

            controller.recordPause()
            controller.playStop()
            Thread.sleep(30)
            controller.onExternalMidi(joystick(0)) // Rest = normal volume
            Thread.sleep(20)
            controller.onExternalMidi(joystick(64)) // Half down = attenuation
            Thread.sleep(20)
            controller.onExternalMidi(joystick(127)) // Fully down = silence
            Thread.sleep(20)
            controller.onExternalMidi(joystick(0)) // Release = normal volume
            controller.recordPause()
            controller.playStop()

            val events = pianoEvents(controller)
            val notes = events.filter { it[1] <= NativeEngine.NOTE_OFF }
            assertEquals(originalNotes, notes)
            val curve = events.filter { it[1] == NativeEngine.CONTROLLER && it[2] == 11 }
            assertTrue(curve.isNotEmpty())
            assertEquals(127, curve.first()[3])
            assertEquals(127, curve.last()[3])
            assertTrue(curve.any { it[3] == 63 })
            assertTrue(curve.any { it[3] == 0 })
            assertTrue(curve.any { it[0] != 0 && it[0] % LoopMath.quantizeStep(16) != 0 })
            // A new project file round-trips the controller events as proper MIDI data.
            assertEquals(events.size, JSONObject(controller.exportDocument(false))
                .getJSONArray("tracks").getJSONObject(4).getJSONArray("events").length())
        } finally {
            controller.newProject(false)
            controller.close()
        }
    }
}
