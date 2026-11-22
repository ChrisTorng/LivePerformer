package tw.idv.christorng.liveperformer

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** App-private project and track-template library with persistent recent-file history. */
class ProjectStorage(context: Context) {
    private val directory = File(context.applicationContext.filesDir, "projects")
        .apply { mkdirs() }
    private val history: SharedPreferences = context.applicationContext.getSharedPreferences(
        "project-library", Context.MODE_PRIVATE)

    data class Entry(val fileName: String, val isTemplate: Boolean, val updatedAt: Long) {
        val displayName get() = fileName.substringBeforeLast('.')
    }

    /** The current editable session may be based on a template but has no project file yet. */
    data class Session(val currentProject: String?, val sourceTemplate: String?)

    companion object {
        const val PROJECT_EXTENSION = ".lvp"
        const val TEMPLATE_EXTENSION = ".lvpt"
        const val TRACK_TEMPLATE = "預設空軌（五種樂器）"
        const val DEMO_TEMPLATE = "內建示範（含錄製內容）"
        private const val CURRENT_PROJECT = "currentProject"
        private const val SOURCE_TEMPLATE = "sourceTemplate"

        fun suggestedName(): String =
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))

        fun fileName(name: String, template: Boolean): String {
            val extension = if (template) TEMPLATE_EXTENSION else PROJECT_EXTENSION
            val cleaned = name.trim()
                .removeSuffix(PROJECT_EXTENSION).removeSuffix(TEMPLATE_EXTENSION)
                .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
                .take(80).trim(' ', '.')
                .ifBlank { suggestedName() }
            return cleaned + extension
        }
    }

    private fun pathOf(fileName: String): File {
        val file = File(directory, fileName).canonicalFile
        require(file.parentFile == directory.canonicalFile) { "專案檔案路徑無效" }
        return file
    }

    private fun recentTime(entry: Entry): Long = history.getLong("recent:" + entry.fileName, 0L)

    private fun markRecent(entry: Entry) {
        history.edit().putLong("recent:" + entry.fileName, System.currentTimeMillis()).apply()
    }

    fun session(): Session {
        val stored = history.getString(CURRENT_PROJECT, null)
        val active = stored?.takeIf {
            it.endsWith(PROJECT_EXTENSION, ignoreCase = true) && pathOf(it).isFile
        }
        return Session(active, history.getString(SOURCE_TEMPLATE, null))
    }

    /** New or bundled-template sessions are distinct from saved project files. */
    fun beginNew(sourceTemplate: String? = null) {
        history.edit().remove(CURRENT_PROJECT).putString(SOURCE_TEMPLATE, sourceTemplate)
            .apply()
        if (sourceTemplate == TRACK_TEMPLATE || sourceTemplate == DEMO_TEMPLATE)
            history.edit().putLong("builtin:" + sourceTemplate, System.currentTimeMillis()).apply()
    }

    fun recentBuiltins(): List<String> = listOf(TRACK_TEMPLATE, DEMO_TEMPLATE)
        .filter { history.getLong("builtin:" + it, 0L) > 0L }
        .sortedByDescending { history.getLong("builtin:" + it, 0L) }

    fun list(template: Boolean? = null): List<Entry> {
        val items = directory.listFiles().orEmpty().filter { it.isFile &&
            (it.name.endsWith(PROJECT_EXTENSION, true) ||
                it.name.endsWith(TEMPLATE_EXTENSION, true))
        }.map { Entry(it.name, it.name.endsWith(TEMPLATE_EXTENSION, true), it.lastModified()) }
        val active = session().currentProject
        return items.filter { template == null || it.isTemplate == template }
            .sortedWith(compareByDescending<Entry> { it.fileName == active }
                .thenByDescending { recentTime(it) }
                .thenByDescending { it.updatedAt })
    }

    /** Only explicitly opened or saved files appear in the recent section. */
    fun recent(template: Boolean? = null, limit: Int = 6): List<Entry> =
        list(template).filter { recentTime(it) > 0L }
            .sortedWith(compareByDescending<Entry> { it.fileName == session().currentProject }
                .thenByDescending { recentTime(it) })
            .take(limit)

    /**
     * Store provenance in project JSON, not in a device-only sidecar. Existing v1
     * project readers ignore this optional top-level field, so export/import retain it.
     */
    fun withProjectMetadata(json: String): String {
        val origin = session().sourceTemplate ?: return json
        return JSONObject(json).put(SOURCE_TEMPLATE, origin).toString()
    }

    /** Activate a file after its contents have been successfully loaded. */
    fun activate(entry: Entry) {
        require(pathOf(entry.fileName).isFile) { "找不到專案檔案" }
        val origin = if (entry.isTemplate) entry.fileName
            else JSONObject(read(entry)).optString(SOURCE_TEMPLATE).ifBlank { null }
        history.edit().putString(CURRENT_PROJECT, if (entry.isTemplate) null else entry.fileName)
            .putString(SOURCE_TEMPLATE, origin).apply()
        markRecent(entry)
    }

    /** New files never overwrite existing work. Only updateCurrent may replace a .lvp. */
    fun save(name: String, template: Boolean, json: String, overwrite: Boolean = false): Entry {
        val nameSafe = fileName(name, template)
        val target = pathOf(nameSafe)
        require(!target.exists() || (overwrite && !template && target.isFile)) {
            "已有同名檔案，請更換名稱；更新原專案請使用「更新」"
        }
        require(!overwrite || target.isFile) { "找不到可更新的專案檔案" }
        val temp = File.createTempFile("saving-", ".tmp", directory)
        try {
            temp.writeText(json, Charsets.UTF_8)
            // Keep existing data intact until a fully written replacement is ready.
            try {
                Files.move(temp.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { temp.delete() }
        return Entry(target.name, template, target.lastModified()).also { markRecent(it) }
    }

    fun saveAsProject(name: String, json: String): Entry {
        val saved = save(name, false, withProjectMetadata(json))
        activate(saved)
        return saved
    }

    /** Save the track-only template without changing the current project's identity. */
    fun saveAsTemplate(name: String, json: String): Entry = save(name, true, json)

    fun updateCurrent(json: String): Entry {
        val name = session().currentProject ?: error("目前沒有可更新的專案，請先另存新檔")
        val saved = save(name.removeSuffix(PROJECT_EXTENSION), false,
            withProjectMetadata(json), overwrite = true)
        activate(saved)
        return saved
    }

    /** Import from SAF without overwriting any existing project or template. */
    fun importExternal(sourceFileName: String, json: String): Entry {
        val isTemplate = sourceFileName.endsWith(TEMPLATE_EXTENSION, ignoreCase = true)
        require(isTemplate || sourceFileName.endsWith(PROJECT_EXTENSION, ignoreCase = true)) {
            "只支援 .lvp 專案與 .lvpt 範本"
        }
        val extension = if (isTemplate) TEMPLATE_EXTENSION else PROJECT_EXTENSION
        val normalizedName = sourceFileName.dropLast(extension.length)
        val cleanName = fileName(normalizedName, isTemplate).removeSuffix(extension)
        for (index in 0..9999) {
            val candidate = if (index == 0) cleanName else "$cleanName ($index)"
            if (!pathOf(candidate + extension).exists()) {
                return save(candidate, isTemplate, json)
            }
        }
        error("專案檔名已使用過多次，無法自動新增")
    }

    fun read(entry: Entry): String {
        require(entry.fileName == fileName(entry.displayName, entry.isTemplate))
        val target = pathOf(entry.fileName)
        return target.readText(Charsets.UTF_8)
    }
}
