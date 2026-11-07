package tw.idv.christorng.liveperformer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** SAF ACTION_OPEN_DOCUMENT filters by MIME, not suffix. Browse an explicitly
 * authorized folder and enforce suffixes ourselves, without storage permission. */
internal enum class FilteredFileKind { SOUNDFONT, PROJECT }

internal data class BrowserEntry(
    val documentId: String,
    val name: String,
    val isFolder: Boolean,
    val size: Long
)

private data class BrowserContents(val entries: List<BrowserEntry> = emptyList(),
                                   val error: String? = null)

internal object FilteredDocumentFiles {
    fun accepts(kind: FilteredFileKind, fileName: String): Boolean = when (kind) {
        FilteredFileKind.SOUNDFONT -> ImportFileTypes.isSoundFontFileName(fileName)
        FilteredFileKind.PROJECT -> ImportFileTypes.isProjectFileName(fileName)
    }

    fun list(context: Context, treeUri: Uri, parentId: String, kind: FilteredFileKind): List<BrowserEntry> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        val files = mutableListOf<BrowserEntry>()
        val resolver = context.contentResolver
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            val idAt = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameAt = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeAt = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeAt = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameAt) ?: continue
                val isFolder = cursor.getString(mimeAt) == DocumentsContract.Document.MIME_TYPE_DIR
                if (isFolder || accepts(kind, name)) {
                    files += BrowserEntry(
                        cursor.getString(idAt), name, isFolder,
                        if (sizeAt >= 0 && !cursor.isNull(sizeAt)) cursor.getLong(sizeAt) else -1
                    )
                }
            }
        } ?: error("無法列出此資料夾的檔案，請改用系統選檔")
        return files.sortedWith(compareByDescending<BrowserEntry> { it.isFolder }
            .thenBy { it.name.lowercase(Locale.ROOT) })
    }
}

@Composable
internal fun FilteredDocumentBrowser(
    treeUri: Uri,
    kind: FilteredFileKind,
    onPick: (Uri) -> Unit,
    onSystemPicker: () -> Unit,
    onClose: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val rootId = remember(treeUri) { DocumentsContract.getTreeDocumentId(treeUri) }
    val folders = remember(treeUri) { mutableStateListOf(rootId to "選取的資料夾") }
    val current = folders.last()
    val contents by produceState<BrowserContents?>(initialValue = null, treeUri, current.first, kind) {
        value = null
        value = withContext(Dispatchers.IO) {
            runCatching {
                BrowserContents(FilteredDocumentFiles.list(context, treeUri, current.first, kind))
            }.getOrElse { BrowserContents(error = it.message ?: "無法讀取資料夾") }
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (kind == FilteredFileKind.SOUNDFONT) "匯入 SoundFont" else "匯入專案") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("僅顯示資料夾與" + (if (kind == FilteredFileKind.SOUNDFONT)
                    " .sf2、.sf3、.zip、.xz 檔案" else " .lvp、.lvpt 檔案"),
                    style = MaterialTheme.typography.bodySmall)
                Text("位置：${folders.joinToString(" / ") { it.second }}",
                    style = MaterialTheme.typography.bodySmall)
                if (folders.size > 1) {
                    TextButton(onClick = { folders.removeAt(folders.lastIndex) }) {
                        Text("‹ 上一層")
                    }
                }
                val view = contents
                when {
                    view == null -> CircularProgressIndicator()
                    view.error != null -> Text(view.error)
                    view.entries.isEmpty() -> Text("這個資料夾沒有符合副檔名的檔案。")
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 330.dp)) {
                        items(view.entries, key = { it.documentId }) { entry ->
                            TextButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    if (entry.isFolder) folders.add(entry.documentId to entry.name)
                                    else onPick(DocumentsContract.buildDocumentUriUsingTree(
                                        treeUri, entry.documentId))
                                }
                            ) {
                                Text((if (entry.isFolder) "▸ " else "") + entry.name,
                                    modifier = Modifier.fillMaxWidth(),
                                    maxLines = 2)
                            }
                        }
                    }
                }
                Text("Android 不允許第三方 App 直接選取 Download 根資料夾；" +
                    "若檔案在下載根目錄或雲端硬碟，請改用系統選檔。",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = onSystemPicker) { Text("系統選檔") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } }
    )
}
