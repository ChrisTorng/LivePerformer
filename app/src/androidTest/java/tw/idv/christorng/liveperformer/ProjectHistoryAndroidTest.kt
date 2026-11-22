package tw.idv.christorng.liveperformer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercise project identity, provenance and overwrite protection with real app storage. */
@RunWith(AndroidJUnit4::class)
class ProjectHistoryAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun projectAndTemplateHistorySurviveActivationAndUpdates() {
        val storage = ProjectStorage(context)
        val prefix = "history-test-" + System.nanoTime()
        var first: ProjectStorage.Entry? = null
        var second: ProjectStorage.Entry? = null
        var template: ProjectStorage.Entry? = null
        val minimalProject = """{"format":"liveperformer.v1","tracks":[]}"""
        try {
            storage.beginNew(ProjectStorage.TRACK_TEMPLATE)
            first = storage.saveAsProject(prefix, minimalProject)
            assertEquals(first.fileName, storage.session().currentProject)
            assertEquals(ProjectStorage.TRACK_TEMPLATE, storage.session().sourceTemplate)
            assertEquals(ProjectStorage.TRACK_TEMPLATE,
                JSONObject(storage.read(first)).getString("sourceTemplate"))

            // Save As must not destroy a previous project. Update changes only that file.
            assertTrue(runCatching { storage.saveAsProject(prefix, minimalProject) }.isFailure)
            assertTrue(storage.read(first).contains("sourceTemplate"))
            storage.updateCurrent("""{"format":"liveperformer.v1","tracks":[],"bpm":125}""")
            assertEquals(125, JSONObject(storage.read(first)).getInt("bpm"))

            template = storage.saveAsTemplate(prefix + "-template", minimalProject)
            assertEquals(first.fileName, storage.session().currentProject)
            storage.activate(template)
            assertNull(storage.session().currentProject)
            assertEquals(template.fileName, storage.session().sourceTemplate)

            second = storage.saveAsProject(prefix + "-derived", minimalProject)
            assertEquals(template.fileName,
                JSONObject(storage.read(second)).getString("sourceTemplate"))
            assertEquals(second.fileName, storage.recent(false).first().fileName)

            storage.activate(first)
            assertEquals(first.fileName, storage.recent(false).first().fileName)
            assertEquals(ProjectStorage.TRACK_TEMPLATE, storage.session().sourceTemplate)
            assertTrue(storage.recent(true).any { it.fileName == template.fileName })
            assertTrue(storage.recentBuiltins().contains(ProjectStorage.TRACK_TEMPLATE))
        } finally {
            listOfNotNull(first, second, template).forEach {
                File(context.filesDir, "projects/" + it.fileName).delete()
            }
            storage.beginNew()
        }
    }
}
