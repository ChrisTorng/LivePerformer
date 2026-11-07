package tw.idv.christorng.liveperformer

/** Resolve generic SF2 INFO/preset names using the source file name and instrument clues. */
object InstrumentNaming {
    fun clean(value: String): String {
        val simple = value.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("(?i)(\\.sf[23]|\\.tar\\.xz|\\.zip|\\.xz)$"), "")
        return simple.replace(Regex("[_\\-]+"), " ")
            .replace(Regex("\\s+"), " ").trim()
    }

    private fun generic(value: String): Boolean {
        val name = clean(value).lowercase()
        return name.isBlank() || name.matches(Regex(
            "(instrument|preset|soundfont|sound *bank|soundfont *bank|sf2|sf3|untitled|default|sample|bank)( *[0-9]+)?"
        ))
    }

    fun bestName(preset: String?, source: String): String {
        val primary = clean(preset.orEmpty())
        val fallback = clean(source)
        return when {
            !generic(primary) -> primary
            !generic(fallback) -> fallback
            else -> "未命名樂器"
        }
    }

    /** A one-preset bank is effectively one instrument: use its user-editable bank label
     * instead of the verbose embedded name. Multi-preset banks retain individual names. */
    fun presetDisplayName(preset: String?, bankTitle: String, presetCount: Int): String =
        if (presetCount == 1 && bankTitle.isNotBlank()) clean(bankTitle)
        else bestName(preset, bankTitle)

    fun category(source: String, program: Int, preset: String): String {
        val name = (if (generic(preset)) source else preset).lowercase()
        val hints = listOf(
            "鋼琴" to listOf("piano", "grand", "upright", "salamander", "鋼琴", "yamaha c", "steinway"),
            "風琴" to listOf("organ", "風琴"),
            "吉他" to listOf("guitar", "吉他"),
            "貝斯" to listOf("bass", "貝斯", "低音吉他"),
            "弦樂" to listOf("violin", "viola", "cello", "string", "弦樂", "提琴"),
            "銅管" to listOf("trumpet", "trombone", "brass", "horn", "銅管"),
            "笛類" to listOf("flute", "recorder", "笛"),
            "打擊" to listOf("marimba", "vibraphone", "xylophone", "打擊"),
            "鼓組" to listOf("drum kit", "drums", "鼓組"),
            "合奏" to listOf("ensemble", "choir", "合奏"),
            "民族" to listOf("erhu", "guzheng", "pipa", "二胡", "古箏", "琵琶")
        )
        return hints.firstOrNull { (_, words) -> words.any { it in name } }?.first
            ?: GmCategories.names[GmCategories.family(program)]
    }
}
