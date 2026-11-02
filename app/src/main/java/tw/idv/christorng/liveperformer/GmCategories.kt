package tw.idv.christorng.liveperformer

/** The standard General MIDI program families: 16 groups of eight programs each. */
object GmCategories {
    val names = listOf(
        "鋼琴", "敲擊", "風琴", "吉他", "貝斯", "弦樂", "合奏", "銅管",
        "簧管", "笛類", "Lead", "Pad", "音效", "民族", "打擊", "其他"
    )
    val symbols = listOf(
        "♬", "♩", "♫", "♮", "♭", "𝄞", "♪", "♬",
        "♫", "♭", "✦", "≈", "✧", "♫", "✹", "⋯"
    )
    // Recognisable, useful first choice for each family, all zero-based GM programs.
    val defaults = listOf(0, 11, 19, 24, 33, 40, 48, 56,
        65, 73, 80, 89, 96, 104, 112, 120)
    fun family(program: Int): Int = (program.coerceIn(0, 127) / 8)
    fun instruments(family: Int): IntRange {
        val group = family.coerceIn(0, 15)
        return group * 8..group * 8 + 7
    }
}
