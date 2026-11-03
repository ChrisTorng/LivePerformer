package tw.idv.christorng.liveperformer

import android.content.Context
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Private project library: only .lvp projects and .lvpt empty-track templates appear. */
class ProjectStorage(context: Context) {
    private val directory = File(context.applicationContext.filesDir, "projects")
        .apply { mkdirs() }

    data class Entry(val fileName: String, val isTemplate: Boolean, val updatedAt: Long) {
        val displayName get() = fileName.substringBeforeLast('.')
    }
    companion object {
        const val PROJECT_EXTENSION = ".lvp"
        const val TEMPLATE_EXTENSION = ".lvpt"

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

    fun list(template: Boolean? = null): List<Entry> {
        val items = directory.listFiles().orEmpty().filter { it.isFile &&
            (it.name.endsWith(PROJECT_EXTENSION, true) ||
                it.name.endsWith(TEMPLATE_EXTENSION, true))
        }.map { Entry(it.name, it.name.endsWith(TEMPLATE_EXTENSION, true), it.lastModified()) }
        return items.filter { template == null || it.isTemplate == template }
            .sortedByDescending { it.updatedAt }
    }

    fun save(name: String, template: Boolean, json: String): Entry {
        val nameSafe = fileName(name, template)
        val target = File(directory, nameSafe)
        val temp = File.createTempFile("saving-", ".tmp", directory)
        try {
            temp.writeText(json, Charsets.UTF_8)
            // Keep existing data intact until a fully written replacement is ready.
            if (target.exists() && !target.delete()) error("Cannot replace prior project")
            if (!temp.renameTo(target)) {
                target.writeText(json, Charsets.UTF_8)
            }
        } finally { temp.delete() }
        return Entry(target.name, template, target.lastModified())
    }

    fun read(entry: Entry): String {
        require(entry.fileName == fileName(entry.displayName, entry.isTemplate))
        val target = File(directory, entry.fileName).canonicalFile
        require(target.parentFile == directory.canonicalFile)
        return target.readText(Charsets.UTF_8)
    }
}
