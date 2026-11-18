package tw.idv.christorng.liveperformer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DrumMapsAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun soundFontDefaultAndPresetOverrideArePersistentAndIndependentOfProjects() {
        val id = "drum-map-test"
        val store = DrumMapStore(context)
        val custom = """
            {
              "format":"liveperformer.drum-map.v1", "name":"One Kit",
              "notes":{"bassDrum":60,"snare":61,"closedHiHat":62,"openHiHat":63,
                       "lowTom":64,"highTom":65,"ride":66,"crash":67}
            }
        """.trimIndent()
        val special = """
            {
              "format":"liveperformer.drum-map.v1", "name":"Special Preset",
              "bank":128, "program":1,
              "notes":{"bassDrum":70,"snare":71,"closedHiHat":72,"openHiHat":73,
                       "lowTom":74,"highTom":75,"ride":76,"crash":77}
            }
        """.trimIndent()
        try {
            store.reset(id)
            assertEquals(46, store.forPreset(id, 128, 1).outputNote(46))
            store.install(id, custom)
            store.install(id, special)
            assertEquals(63, store.forPreset(id, 128, 0).outputNote(46))
            assertEquals(73, store.forPreset(id, 128, 1).outputNote(46))
            assertEquals(73, DrumMapStore(context).forPreset(id, 128, 1).outputNote(46))
            assertTrue(store.description(id).contains("預設對應"))
        } finally {
            store.reset(id)
            assertEquals(46, store.forPreset(id, 128, 1).outputNote(46))
        }
    }

    @Test fun rejectsPartialOrInvalidHiHatMappings() {
        val invalid = """
            {"format":"liveperformer.drum-map.v1","name":"Bad","notes":{
            "bassDrum":36,"snare":38,"closedHiHat":42,"openHiHat":42,
            "lowTom":45,"highTom":50,"ride":51,"crash":49}}
        """.trimIndent()
        assertThrows(IllegalArgumentException::class.java) { DrumMap.fromJson(invalid) }
    }
}
