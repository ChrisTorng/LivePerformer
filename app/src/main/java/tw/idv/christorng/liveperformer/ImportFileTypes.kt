package tw.idv.christorng.liveperformer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.util.Locale

/** SAF filters by MIME type (provider-dependent), so always validate the file name as well. */
object ImportFileTypes {
    val soundFontMimeTypes = arrayOf(
        "application/octet-stream", "audio/x-sf2", "audio/x-sf3",
        "application/x-sf2", "application/x-sf3",
        "application/zip", "application/x-zip-compressed",
        "application/x-xz", "application/x-tar"
    )
    val projectMimeTypes = arrayOf("application/octet-stream", "application/json")

    fun isSoundFontFileName(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return listOf(".sf2", ".sf3", ".zip", ".xz").any(lower::endsWith)
    }

    fun isProjectFileName(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.endsWith(".lvp") || lower.endsWith(".lvpt")
    }

    fun soundFontExtension(name: String): String? {
        val lower = name.lowercase(Locale.ROOT)
        return when {
            lower.endsWith(".sf2") -> "sf2"
            lower.endsWith(".sf3") -> "sf3"
            else -> null
        }
    }

    fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME),
            null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && !cursor.isNull(index)) {
                    val name = cursor.getString(index)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: ""
    }
}
