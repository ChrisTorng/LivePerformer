package tw.idv.christorng.liveperformer

import android.content.Context
import android.net.Uri
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream

/** Imports SF2/SF3 directly or from ZIP, TAR.XZ and single-file XZ archives.
 * Archive paths never become filesystem paths. The expanded size is capped at 2 GB. */
object SoundFontImporter {
    private const val MAX_SIZE = 2_000_000_000L

    fun extract(context: Context, uri: Uri): File {
        val name = ImportFileTypes.displayName(context, uri)
        require(ImportFileTypes.isSoundFontFileName(name)) {
            "只接受 SF2、SF3、ZIP 或 XZ 音色檔"
        }
        val base = "imported-${System.currentTimeMillis()}-${UUID.randomUUID()}"
        val part = File(context.filesDir, "$base.part")
        var target: File? = null
        var extension = "sf2"
        try {
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
                                copyLimited(archive, part)
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
                                copyLimited(decoded, part)
                            } else {
                                TarArchiveInputStream(decoded).use { archive ->
                                    var valid = false
                                    while (true) {
                                        val entry = archive.nextEntry ?: break
                                        val type = ImportFileTypes.soundFontExtension(entry.name)
                                        if (entry.isFile && type != null) {
                                            extension = type
                                            copyLimited(archive, part)
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
                        copyLimited(source, part)
                    }
                }
            }
            check(part.length() >= 128) { "SoundFont 檔案內容過短" }
            part.inputStream().use { input ->
                val head = ByteArray(12)
                check(input.read(head) == head.size &&
                    String(head, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                    String(head, 8, 4, Charsets.US_ASCII) == "sfbk") {
                    "檔案不是有效的 SoundFont（RIFF/sfbk）"
                }
            }
            target = File(context.filesDir, "$base.$extension")
            check(part.renameTo(target)) { "無法儲存 SoundFont" }
            // The bundled Android FluidSynth may lack the optional Vorbis decoder.
            // Reject an unusable SF3 before placing it in the persistent library.
            if (extension == "sf3") {
                check(NativeEngine.validateSoundFont(target.absolutePath)) {
                    "此裝置的 FluidSynth 無法載入 SF3（可能缺少 Vorbis 解碼、檔案損壞或記憶體不足）"
                }
            }
            return target
        } catch (error: Exception) {
            part.delete()
            target?.delete()
            throw error
        }
    }

    private fun copyLimited(input: InputStream, target: File) {
        target.outputStream().use { output ->
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                check(total <= MAX_SIZE) { "SoundFont 超過 2 GB 安全限制" }
                output.write(buf, 0, n)
            }
        }
    }
}
