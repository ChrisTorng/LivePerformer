package tw.idv.christorng.liveperformer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

data class SoundFontItem(val id: String, val title: String, val file: File, val isBundled: Boolean) {
    val sizeMiB: Long get() = file.length() / (1024 * 1024)
}

/** Persistent SF2/SF3 registry. Supports files imported with v0.1.2 without re-importing them. */
class SoundFontLibrary(private val context: Context) {
    private val prefs = context.getSharedPreferences("soundfont-library", Context.MODE_PRIVATE)
    private val directory get() = context.filesDir

    fun all(): List<SoundFontItem> {
        val result = mutableListOf<SoundFontItem>()
        val bundled = File(directory, "GeneralUser-GS.sf2")
        if (bundled.isFile) result += SoundFontItem("gm", "GeneralUser GS", bundled, true)
        directory.listFiles()?.filter {
            it.isFile && it.name.startsWith("imported-") && ImportFileTypes.soundFontExtension(it.name) != null
        }?.sortedBy { it.name }?.forEach { file ->
            val custom = prefs.getString("customTitle:" + file.name, null)
            val stored = runCatching { SoundFontPresets.read(file).title }.getOrNull()
            val sourceName = prefs.getString("source:" + file.name, null)
                ?.substringBeforeLast(':')
                ?: file.name.removePrefix("imported-")
            val label = custom ?: InstrumentNaming.bestName(stored, sourceName)
            result += SoundFontItem(file.name, label, file, false)
        }
        return result
    }

    fun preferredId(): String = prefs.getString("selected", "gm") ?: "gm"
    fun rememberSelected(id: String) {
        prefs.edit().putString("selected", id).apply()
    }

    private fun sourceMetadata(uri: Uri): Pair<String, Long> {
        var display = uri.lastPathSegment?.substringAfterLast('/') ?: "Imported SoundFont"
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val titleCol = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (titleCol >= 0 && !cursor.isNull(titleCol)) display = cursor.getString(titleCol)
                if (sizeCol >= 0 && !cursor.isNull(sizeCol)) size = cursor.getLong(sizeCol)
            }
        }
        return display to size
    }

    fun existingFor(uri: Uri): SoundFontItem? {
        val (name, size) = sourceMetadata(uri)
        if (size < 0) return null
        return all().firstOrNull {
            !it.isBundled && prefs.getString("source:" + it.id, null) == "$name:$size"
        }
    }

    fun register(uri: Uri, file: File): SoundFontItem {
        val (sourceName, sourceSize) = sourceMetadata(uri)
        val title = InstrumentNaming.bestName(
            runCatching { SoundFontPresets.read(file).title }.getOrNull(),
            sourceName
        )
        prefs.edit()
            .putString("title:" + file.name, title)
            .putString("source:" + file.name, "$sourceName:$sourceSize")
            .apply()
        return SoundFontItem(file.name, title, file, false)
    }

    /** Custom display label is independent of the original source or embedded SoundFont name. */
    fun rename(item: SoundFontItem, requested: String): SoundFontItem {
        require(!item.isBundled && item.id.startsWith("imported-")) {
            "內建音色庫名稱不能修改"
        }
        require(item.file.isFile && item.file.canonicalFile.parentFile == directory.canonicalFile) {
            "找不到指定的音色庫"
        }
        val label = requested.trim().replace(Regex("[\\r\\n\\t]+"), " ").take(90)
        require(label.isNotBlank()) { "請輸入音色庫名稱" }
        prefs.edit().putString("customTitle:" + item.id, label).apply()
        return item.copy(title = label)
    }

    fun remove(item: SoundFontItem): Boolean {
        if (item.isBundled || !item.id.startsWith("imported-")) return false
        if (!item.file.canonicalFile.parentFile.equals(directory.canonicalFile)) return false
        if (!item.file.delete()) return false
        prefs.edit().remove("title:" + item.id).remove("source:" + item.id)
            .remove("customTitle:" + item.id).apply()
        return true
    }
}
