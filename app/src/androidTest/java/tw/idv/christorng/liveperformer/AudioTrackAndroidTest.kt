package tw.idv.christorng.liveperformer

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AudioTrackAndroidTest {
    @Test fun projectRoundTripAndMixerControls() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val controller = LoopController(context)
        try {
            controller.newProject(true)
            val tracks = controller.state.value.tracks
            assertEquals(6, tracks.size)
            val audio = tracks.last()
            assertTrue(audio.isAudio)
            assertTrue(controller.addAudioTrack())
            val added = controller.state.value.tracks.last()
            assertTrue(added.isAudio)
            assertNotEquals(audio.channel, added.channel)
            controller.renameTrack(audio.id, "Voice")
            controller.setTrackVolume(audio.id, 80)
            controller.setTrackMuted(audio.id, true)
            val project = JSONObject(controller.exportDocument(false))
            val saved = project.getJSONArray("tracks").getJSONObject(5)
            val pcm = ShortArray(128) { if (it == 64) Short.MAX_VALUE else 0 }
            saved.put("pcm", LoopPcm.encode(pcm))
            controller.importDocument(project.toString())
            val restored = controller.state.value.tracks.first { it.id == audio.id }
            assertEquals("Voice", restored.name)
            assertEquals(80, restored.volume)
            assertTrue(restored.muted)
            assertTrue(restored.waveform[64] > .99f)
            controller.setTrackSolo(audio.id, true)
            assertFalse(controller.state.value.tracks.first { it.id == audio.id }.muted)
            val template = JSONObject(controller.exportDocument(true))
            assertFalse(template.getJSONArray("tracks").getJSONObject(5).has("pcm"))
            controller.clearTrack(audio.id)
            assertTrue(controller.state.value.tracks.first { it.id == audio.id }.waveform.isEmpty())
        } finally {
            controller.newProject(false)
            controller.close()
        }
    }
}
