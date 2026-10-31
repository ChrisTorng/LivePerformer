package tw.idv.christorng.liveperformer

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

data class InstrumentPreset(val bank: Int, val program: Int, val name: String) {
    val displayName: String get() = if (bank == 0) "${program + 1}. $name"
        else "Bank $bank · ${program + 1}. $name"
}

/** Read only the SF2 INFO and preset headers; never load gigabyte sample payloads. */
object SoundFontPresets {
    private val latin1 = Charsets.ISO_8859_1
    private fun RandomAccessFile.fourcc(): String {
        val b = ByteArray(4)
        readFully(b)
        return String(b, Charsets.US_ASCII)
    }
    private fun RandomAccessFile.u32(): Long {
        val a = readUnsignedByte()
        val b = readUnsignedByte()
        val c = readUnsignedByte()
        val d = readUnsignedByte()
        return (a.toLong() or (b.toLong() shl 8) or (c.toLong() shl 16) or (d.toLong() shl 24)) and 0xffffffffL
    }
    private fun name(bytes: ByteArray, from: Int, length: Int): String =
        String(bytes, from, length, latin1).substringBefore('\u0000').trim()

    data class Info(val title: String?, val presets: List<InstrumentPreset>)

    fun read(file: File): Info = RandomAccessFile(file, "r").use { raf ->
        require(raf.length() >= 12 && raf.fourcc() == "RIFF") { "不是 SF2 RIFF 檔案" }
        raf.u32()
        require(raf.fourcc() == "sfbk") { "無效的 SoundFont 格式" }
        var title: String? = null
        val presets = mutableListOf<InstrumentPreset>()
        while (raf.filePointer + 12 <= raf.length()) {
            val kind = raf.fourcc()
            val size = raf.u32()
            val payload = raf.filePointer
            val end = payload + size
            require(size >= 0 && end <= raf.length()) { "SF2 區塊長度錯誤" }
            if (kind == "LIST" && size >= 4) {
                val listType = raf.fourcc()
                while (raf.filePointer + 8 <= end) {
                    val id = raf.fourcc()
                    val childSize = raf.u32()
                    val start = raf.filePointer
                    require(start + childSize <= end) { "SF2 子區塊長度錯誤" }
                    when {
                        listType == "INFO" && id == "INAM" && childSize in 1..4096 -> {
                            val b = ByteArray(childSize.toInt())
                            raf.readFully(b)
                            title = name(b, 0, b.size).ifBlank { null }
                        }
                        listType == "pdta" && id == "phdr" -> {
                            require(childSize % 38L == 0L) { "SF2 phdr 格式錯誤" }
                            val count = (childSize / 38L - 1).coerceIn(0L, 5000L).toInt()
                            repeat(count) {
                                val b = ByteArray(38)
                                raf.readFully(b)
                                val p = (b[20].toInt() and 255) or ((b[21].toInt() and 255) shl 8)
                                val bank = (b[22].toInt() and 255) or ((b[23].toInt() and 255) shl 8)
                                val presetName = name(b, 0, 20)
                                if (p in 0..127 && presetName.isNotBlank() && presetName != "EOP")
                                    presets.add(InstrumentPreset(bank, p, presetName))
                            }
                        }
                    }
                    raf.seek(start + childSize + (childSize and 1L))
                }
            }
            raf.seek(end + (size and 1L))
        }
        Info(title, presets.distinctBy { it.bank to it.program }
            .sortedWith(compareBy<InstrumentPreset> { it.bank }.thenBy { it.program }))
    }
}
