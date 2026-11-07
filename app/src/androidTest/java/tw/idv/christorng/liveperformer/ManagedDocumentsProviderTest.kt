package tw.idv.christorng.liveperformer

import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Integration coverage for Android Files/DocumentsUI-facing private library. */
@RunWith(AndroidJUnit4::class)
class ManagedDocumentsProviderTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun rootAndFoldersAreVisibleWithoutDownloadPermission() {
        val uri = DocumentsContract.buildRootsUri(LivePerformerDocumentsProvider.AUTHORITY)
        context.contentResolver.query(uri, null, null, null, null).use { cursor ->
            requireNotNull(cursor)
            assertTrue(cursor.moveToFirst())
            val rootId = cursor.getString(
                cursor.getColumnIndexOrThrow(DocumentsContract.Root.COLUMN_DOCUMENT_ID))
            assertEquals(LivePerformerDocumentsProvider.ROOT, rootId)
        }
        val children = DocumentsContract.buildChildDocumentsUri(
            LivePerformerDocumentsProvider.AUTHORITY, LivePerformerDocumentsProvider.ROOT)
        val names = mutableListOf<String>()
        context.contentResolver.query(children, null, null, null, null).use { cursor ->
            requireNotNull(cursor)
            val col = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            while (cursor.moveToNext()) names.add(cursor.getString(col))
        }
        assertTrue(names.containsAll(listOf("fonts", "inbox", "projects", "templates")))
    }

    @Test fun fileManagerCanCopySf3IntoInboxAndAppCanReadIt() {
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUri(
            LivePerformerDocumentsProvider.AUTHORITY, LivePerformerDocumentsProvider.INBOX)
        val name = "sf3-inbox-test-${System.nanoTime()}.sf3"
        val uri = DocumentsContract.createDocument(
            resolver, parent, "application/octet-stream", name)
        assertNotNull(uri)
        try {
            resolver.openOutputStream(uri!!)?.use { it.write(byteArrayOf(0x52, 0x49, 0x46, 0x46)) }
            assertEquals(name, ImportFileTypes.displayName(context, uri))
            assertTrue(LivePerformerDocumentsProvider.pendingSoundFonts(context)
                .any { it.name == name })
            resolver.openInputStream(uri)?.use { assertEquals(0x52, it.read()) }
        } finally {
            if (uri != null) DocumentsContract.deleteDocument(resolver, uri)
        }
    }

    @Test fun appProjectSavesAppearInProviderAndExternalImportsDoNotOverwrite() {
        val storage = ProjectStorage(context)
        val name = "provider-duplicate-${System.nanoTime()}.lvp"
        val first = storage.importExternal(name, "{\"version\":1}")
        val second = storage.importExternal(name, "{\"version\":2}")
        try {
            assertNotEquals(first.fileName, second.fileName)
            assertEquals("{\"version\":1}", storage.read(first))
            assertEquals("{\"version\":2}", storage.read(second))
            val children = DocumentsContract.buildChildDocumentsUri(
                LivePerformerDocumentsProvider.AUTHORITY, LivePerformerDocumentsProvider.PROJECTS)
            val found = mutableListOf<String>()
            context.contentResolver.query(children, null, null, null, null).use { cursor ->
                requireNotNull(cursor)
                val col = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) found.add(cursor.getString(col))
            }
            assertTrue(found.containsAll(listOf(first.fileName, second.fileName)))
        } finally {
            File(context.filesDir, "projects/${first.fileName}").delete()
            File(context.filesDir, "projects/${second.fileName}").delete()
        }
    }

    @Test fun rejectsNonSoundFontFilesInImportInbox() {
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUri(
            LivePerformerDocumentsProvider.AUTHORITY, LivePerformerDocumentsProvider.INBOX)
        val wrong = runCatching {
            DocumentsContract.createDocument(resolver, parent, "application/octet-stream", "wrong.mp4")
        }
        assertTrue("Only permitted sound bank extensions should be writable",
            wrong.isFailure || wrong.getOrNull() == null)
    }
}
