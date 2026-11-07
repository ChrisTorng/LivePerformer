package tw.idv.christorng.liveperformer

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException
import java.util.Locale

/**
 * Presents the existing private sound bank/project library as one SAF document root.
 *
 * This is a logical location visible in Android's system Files picker:
 *     LivePerformer / 音色庫 (read-only installed SF2)
 *                   / 待匯入音色 (writable drop folder for SF2/SF3/ZIP/XZ)
 *                   / 專案 (existing .lvp files)
 *                   / 範本 (existing .lvpt files)
 *
 * It does not request access to restricted Download or storage roots, does not
 * copy already-installed multi-GB sound banks, and preserves v0.4.x file paths.
 */
class LivePerformerDocumentsProvider : DocumentsProvider() {
    companion object {
        const val AUTHORITY = "tw.idv.christorng.liveperformer.documents"
        const val ROOT = "liveperformer"
        const val FONTS = "fonts"
        const val INBOX = "inbox"
        const val PROJECTS = "projects"
        const val TEMPLATES = "templates"
        private val DIRECTORIES = listOf(FONTS, INBOX, PROJECTS, TEMPLATES)

        private val ROOT_COLUMNS = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_AVAILABLE_BYTES
        )
        private val DOC_COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS
        )

        fun importedSoundFontUri(name: String): Uri =
            DocumentsContract.buildDocumentUri(AUTHORITY, "$INBOX:$name")

        /** Kept private to the App; files are not duplicated into public Downloads. */
        fun inboxDirectory(context: Context): File =
            File(context.filesDir, "liveperformer-imports/soundfonts").apply { mkdirs() }

        fun pendingSoundFonts(context: Context): List<File> =
            inboxDirectory(context).listFiles().orEmpty().filter {
                it.isFile && ImportFileTypes.isSoundFontFileName(it.name)
            }.sortedBy { it.name.lowercase(Locale.ROOT) }

        private fun childId(folder: String, name: String): String = "$folder:$name"

        private fun isSafeName(name: String): Boolean =
            name.isNotBlank() && name != "." && name != ".." &&
                name.length <= 160 && !name.any { it == '/' || it == '\\' || it.code < 32 }

        private fun accepts(folder: String, name: String): Boolean = when (folder) {
            FONTS -> name == "GeneralUser-GS.sf2" ||
                (name.startsWith("imported-") && ImportFileTypes.soundFontExtension(name) != null)
            INBOX -> ImportFileTypes.isSoundFontFileName(name)
            PROJECTS -> name.endsWith(".lvp", ignoreCase = true)
            TEMPLATES -> name.endsWith(".lvpt", ignoreCase = true)
            else -> false
        }
    }

    override fun onCreate(): Boolean {
        val c = context ?: return false
        inboxDirectory(c)
        File(c.filesDir, "projects").mkdirs()
        return true
    }

    private fun folderPath(folder: String): File {
        val c = context ?: throw FileNotFoundException("App storage unavailable")
        return when (folder) {
            FONTS -> c.filesDir
            INBOX -> inboxDirectory(c)
            PROJECTS, TEMPLATES -> File(c.filesDir, "projects").apply { mkdirs() }
            else -> throw FileNotFoundException("Unknown folder")
        }
    }

    private fun splitId(documentId: String): Pair<String, String> {
        val index = documentId.indexOf(':')
        if (index < 1) throw FileNotFoundException("Invalid document id")
        val folder = documentId.substring(0, index)
        val name = documentId.substring(index + 1)
        if (folder !in DIRECTORIES || !isSafeName(name) || !accepts(folder, name)) {
            throw FileNotFoundException("Document is outside LivePerformer")
        }
        return folder to name
    }

    private fun resolveFile(documentId: String): File {
        val (folder, name) = splitId(documentId)
        val parent = folderPath(folder).canonicalFile
        val file = File(parent, name).canonicalFile
        if (file.parentFile != parent || !file.isFile) {
            throw FileNotFoundException("SoundFont or project not found")
        }
        return file
    }

    private fun displayName(folder: String, name: String): String {
        if (folder != FONTS || !name.startsWith("imported-")) return name
        val c = context ?: return name
        val prefs = c.getSharedPreferences("soundfont-library", Context.MODE_PRIVATE)
        val title = prefs.getString("customTitle:$name", null)
            ?: prefs.getString("title:$name", null)
        val suffix = name.substringAfterLast('.', "sf2")
        return title?.takeIf { it.isNotBlank() }?.let { "$it.$suffix" } ?: name
    }

    private fun row(cursor: MatrixCursor, id: String, label: String,
                    mime: String, size: Long = 0L, modified: Long = 0L, flags: Int = 0) {
        cursor.newRow().apply {
            add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, id)
            add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, label)
            add(DocumentsContract.Document.COLUMN_MIME_TYPE, mime)
            add(DocumentsContract.Document.COLUMN_SIZE, size)
            add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, modified)
            add(DocumentsContract.Document.COLUMN_FLAGS, flags)
        }
    }

    private fun folderLabel(folder: String): String = when (folder) {
        FONTS -> "音色庫（已匯入）"
        INBOX -> "待匯入音色"
        PROJECTS -> "專案"
        TEMPLATES -> "音軌範本"
        else -> "LivePerformer"
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        return MatrixCursor(projection ?: ROOT_COLUMNS).apply {
            newRow().apply {
                add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT)
                add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT)
                add(DocumentsContract.Root.COLUMN_TITLE, "LivePerformer")
                add(DocumentsContract.Root.COLUMN_SUMMARY, "音色庫、待匯入音色、專案、音軌範本")
                add(DocumentsContract.Root.COLUMN_FLAGS,
                    DocumentsContract.Root.FLAG_LOCAL_ONLY or
                        DocumentsContract.Root.FLAG_SUPPORTS_CREATE or
                        DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD)
                add(DocumentsContract.Root.COLUMN_ICON, R.mipmap.ic_launcher)
                add(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES,
                    context?.filesDir?.usableSpace ?: 0L)
            }
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        return MatrixCursor(projection ?: DOC_COLUMNS).apply {
            when (documentId) {
                ROOT -> row(this, ROOT, "LivePerformer", DocumentsContract.Document.MIME_TYPE_DIR)
                in DIRECTORIES -> row(this, documentId, folderLabel(documentId),
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    flags = if (documentId != FONTS)
                        DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE else 0)
                else -> {
                    val (folder, name) = splitId(documentId)
                    val file = resolveFile(documentId)
                    row(this, documentId, displayName(folder, name), "application/octet-stream",
                        file.length(), file.lastModified(),
                        if (folder == FONTS) 0 else
                            DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
                                DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                                DocumentsContract.Document.FLAG_SUPPORTS_RENAME)
                }
            }
        }
    }

    override fun queryChildDocuments(
        parentDocumentId: String, projection: Array<out String>?, sortOrder: String?
    ): Cursor {
        return MatrixCursor(projection ?: DOC_COLUMNS).apply {
            if (parentDocumentId == ROOT) {
                for (folder in DIRECTORIES) {
                    row(this, folder, folderLabel(folder),
                        DocumentsContract.Document.MIME_TYPE_DIR,
                        flags = if (folder != FONTS)
                            DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE else 0)
                }
                return@apply
            }
            if (parentDocumentId !in DIRECTORIES) {
                throw FileNotFoundException("Not a LivePerformer folder")
            }
            val folder = parentDocumentId
            val files = folderPath(folder).listFiles().orEmpty()
                .filter { it.isFile && accepts(folder, it.name) }
                .sortedBy { it.name.lowercase(Locale.ROOT) }
            for (file in files) {
                row(this, childId(folder, file.name), displayName(folder, file.name),
                    "application/octet-stream", file.length(), file.lastModified(),
                    if (folder == FONTS) 0 else
                        DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
                            DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                            DocumentsContract.Document.FLAG_SUPPORTS_RENAME)
            }
        }
    }

    override fun openDocument(
        documentId: String, mode: String, signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val (folder, _) = splitId(documentId)
        if ((mode.contains('w') || mode.contains('a') || mode.contains('+')) && folder == FONTS) {
            throw FileNotFoundException("Installed banks are read-only")
        }
        return ParcelFileDescriptor.open(resolveFile(documentId), ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String,
                                displayName: String): String {
        if (parentDocumentId !in listOf(INBOX, PROJECTS, TEMPLATES) ||
            !isSafeName(displayName) || !accepts(parentDocumentId, displayName)) {
            throw FileNotFoundException("Unsupported filename or folder")
        }
        val target = File(folderPath(parentDocumentId), displayName)
        if (!target.createNewFile()) throw FileNotFoundException("File already exists")
        return childId(parentDocumentId, displayName)
    }

    override fun deleteDocument(documentId: String) {
        val (folder, _) = splitId(documentId)
        if (folder == FONTS || !resolveFile(documentId).delete()) {
            throw FileNotFoundException("Cannot delete the selected document")
        }
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val (folder, _) = splitId(documentId)
        if (folder == FONTS || !isSafeName(displayName) || !accepts(folder, displayName)) {
            throw FileNotFoundException("Unsupported filename or folder")
        }
        val source = resolveFile(documentId)
        val target = File(folderPath(folder), displayName)
        if (source != target) {
            if (target.exists() || !source.renameTo(target)) {
                throw FileNotFoundException("Cannot rename document")
            }
        }
        return childId(folder, target.name)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        if (parentDocumentId == ROOT) return documentId in DIRECTORIES ||
            runCatching { splitId(documentId) }.isSuccess
        return runCatching { splitId(documentId).first == parentDocumentId }.getOrDefault(false)
    }
}
