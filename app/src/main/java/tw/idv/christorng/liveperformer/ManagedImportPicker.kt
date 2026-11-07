package tw.idv.christorng.liveperformer

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Imports from LivePerformer's own SAF drop folder; no tree picker or root permission. */
@Composable
internal fun ManagedImportPicker(
    context: Context,
    onSelect: (Uri) -> Unit,
    onClose: () -> Unit,
    onSystemPicker: () -> Unit
) {
    var revision by remember { mutableIntStateOf(0) }
    val entries by produceState<List<File>?>(
        initialValue = null, key1 = revision
    ) {
        value = null
        value = withContext(Dispatchers.IO) {
            LivePerformerDocumentsProvider.pendingSoundFonts(context)
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("LivePerformer 待匯入音色") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("從 Android Files 的「LivePerformer → 待匯入音色」複製或移入檔案後，" +
                    "即可在這裡依副檔名選取，不需要授權整個 Download。",
                    style = MaterialTheme.typography.bodySmall)
                Text("支援：SF2、SF3、ZIP、XZ",
                    style = MaterialTheme.typography.bodySmall)
                when {
                    entries == null -> CircularProgressIndicator()
                    entries!!.isEmpty() -> Text("目前沒有待匯入檔案。從 Downloads 直接選取也可以。")
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                        items(entries!!, key = { it.name }) { file ->
                            TextButton(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { onSelect(
                                    LivePerformerDocumentsProvider.importedSoundFontUri(file.name)
                                ) }
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(file.name, maxLines = 2)
                                    Text(String.format(Locale.US, "%.1f MiB",
                                        file.length() / 1048576.0),
                                        style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { revision++ }) { Text("重新整理") }
            TextButton(onClick = onSystemPicker) { Text("從 Downloads 選檔") }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text("取消") }
        }
    )
}
