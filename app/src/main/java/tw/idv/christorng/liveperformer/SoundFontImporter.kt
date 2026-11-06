package tw.idv.christorng.liveperformer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream

/** Compression expands on a worker thread; updates are throttled to avoid flooding Compose. */
data class SoundFontImportProgress(
    val stage: String,
    val completedBytes: Long = 0L,
    val totalBytes: Long? = null
) {
    val fraction: Float? get() = totalBytes?.takeIf { it > 0 }?.let { total ->
        (completedBytes.toDouble() / total).coerceIn(0.0, 1.0).toFloat()
    }
}

/** Import SF2/SF3 directly or from ZIP/TAR.XZ/XZ without trusting archive paths. */
object SoundFontImporter {
    private const val MAX_SIZE = 2_000_000_000L
    private const val UPDATE_INTERVAL_NS = 200_000_000L

    fun extract(
        context: Context,
        uri: Uri,
        onProgress: (SoundFontImportProgress) -> Unit = {}
    ): File {
        val name = ImportFileTypes.displayName(context, uri)
        require(ImportFileTypes.isSoundFontFileName(name)) {
            "只接受 SF2、SF3、ZIP 或 XZ 音色檔"
        }
        // Remove incomplete previous attempts, including files left after process death.
        context.filesDir.listFiles()?.filter {
            it.isFile && it.name.startsWith("imported-") && it.name.endsWith(".part")
        }?.forEach { it.delete() }
        val base = "imported-${System.currentTimeMillis()}-${UUID.randomUUID()}"
        val part = File(context.filesDir, "$base.part")
        var target: File? = null
        var extension = "sf2"
        try {
            onProgress(SoundFontImportProgress("分析檔案：$name"))
            val sourceSize = context.contentResolver.query(
                uri, arrayOf(OpenableColumns.SIZE), null, null, null
            )?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index))
                    cursor.getLong(index).takeIf { it > 0 } else null
            }
            val raw = context.contentResolver.openInputStream(uri) ?: error("無法開啟檔案")
            BufferedInputStream(raw).use { source ->
                source.mark(12)
                val signature = ByteArray(12)
                val found = source.read(signature)
                source.reset()
                when {
                    found >= 4 && signature[0] == 'P'.code.toByte() &&
                        signature[1] == 'K'.code.toByte() -> ZipInputStream(source).use { archive ->
                        var valid = false
                        while (true) {
                            val entry = archive.nextEntry ?: break
                            val type = ImportFileTypes.soundFontExtension(entry.name)
                            if (!entry.isDirectory && type != null) {
                                extension = type
                                copyLimited(archive, part, "解壓縮 ZIP：${entry.name}", null, onProgress)
                                valid = true
                                break
                            }
                            archive.closeEntry()
                        }
                        check(valid) { "ZIP 裡面找不到 SF2 或 SF3" }
                    }
                    found >= 6 && (signature[0].toInt() and 255) == 0xfd &&
                        signature[1] == '7'.code.toByte() &&
                        signature[2] == 'z'.code.toByte() &&
                        signature[3] == 'X'.code.toByte() &&
                        signature[4] == 'Z'.code.toByte() -> {
                        BufferedInputStream(XZInputStream(source)).use { decoded ->
                            decoded.mark(12)
                            val head = ByteArray(12)
                            val count = decoded.read(head)
                            decoded.reset()
                            if (count == 12 &&
                                String(head, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                                String(head, 8, 4, Charsets.US_ASCII) == "sfbk") {
                                if (name.lowercase(Locale.ROOT).endsWith(".sf3.xz"))
                                    extension = "sf3"
                                copyLimited(decoded, part, "解壓縮 XZ", null, onProgress)
                            } else {
                                TarArchiveInputStream(decoded).use { archive ->
                                    var valid = false
                                    while (true) {
                                        val entry = archive.nextEntry ?: break
                                        val type = ImportFileTypes.soundFontExtension(entry.name)
                                        if (entry.isFile && type != null) {
                                            extension = type
                                            copyLimited(archive, part,
                                                "解壓縮 TAR.XZ：${entry.name}", null, onProgress)
                                            valid = true
                                            break
                                        }
                                    }
                                    check(valid) { "TAR.XZ 裡面找不到 SF2 或 SF3" }
                                }
                            }
                        }
                    }
                    else -> {
                        extension = ImportFileTypes.soundFontExtension(name)
                            ?: error("檔案不是可辨識的 SF2/SF3、ZIP 或 XZ")
                        copyLimited(source, part, "複製 ${extension.uppercase(Locale.ROOT)}",
                            sourceSize, onProgress)
                    }
                }
            }
            onProgress(SoundFontImportProgress("驗證 SoundFont 檔案結構", part.length()))
            check(part.length() >= 128) { "SoundFont 檔案內容過短" }
            part.inputStream().use { input ->
                val head = ByteArray(12)
                check(input.read(head) == head.size &&
                    String(head, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                    String(head, 8, 4, Charsets.US_ASCII) == "sfbk") {
                    "檔案不是有效的 SoundFont（RIFF/sfbk）"
                }
            }
            val destination = File(context.filesDir, "$base.$extension")
            target = destination
            check(part.renameTo(destination)) { "無法儲存 SoundFont" }
            if (extension == "sf3") {
                val converted = File(context.filesDir, "$base.sf2")
                try {
                    onProgress(SoundFontImportProgress(
                        "SF3 需要離線解壓為 SF2（需額外儲存空間）"))
                    Sf3ToSf2Converter.convert(destination, converted, onProgress)
                    onProgress(SoundFontImportProgress("驗證轉換後的 SF2", converted.length()))
                    check(NativeEngine.validateSoundFont(converted.absolutePath)) {
                        "轉換完成，但 FluidSynth 無法載入 SF2（檔案可能損壞或記憶體不足）"
                    }
                    target = converted
                    destination.delete()
                    onProgress(SoundFontImportProgress("SF3 已轉換為 SF2，準備啟用音色庫"))
                    return converted
                } catch (error: Exception) {
                    converted.delete()
                    throw error
                }
            }
            onProgress(SoundFontImportProgress("完成檔案匯入，準備音源引擎", destination.length()))
            return destination
        } catch (error: Exception) {
            part.delete()
            target?.delete()
            throw error
        }
    }

    private fun copyLimited(
        input: InputStream,
        target: File,
        stage: String,
        totalBytes: Long?,
        onProgress: (SoundFontImportProgress) -> Unit
    ) {
        target.outputStream().use { output ->
            val buf = ByteArray(64 * 1024)
            var total = 0L
            var lastReport = System.nanoTime()
            onProgress(SoundFontImportProgress(stage, 0, totalBytes))
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                check(total <= MAX_SIZE) { "SoundFont 解壓後超過 2 GB 限制" }
                output.write(buf, 0, n)
                val now = System.nanoTime()
                if (now - lastReport >= UPDATE_INTERVAL_NS) {
                    onProgress(SoundFontImportProgress(stage, total, totalBytes))
                    lastReport = now
                }
            }
            onProgress(SoundFontImportProgress(stage, total, totalBytes))
        }
    }
}
