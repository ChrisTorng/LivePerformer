package tw.idv.christorng.liveperformer

import android.content.Context
import android.net.Uri
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.File
import java.util.zip.ZipInputStream

/** Supports original SF2, ZIP containing SF2, and FreePats TAR.XZ.
 * Extracts a single SF2 into private app storage, never trusting archive paths. */
object SoundFontImporter {
    private const val MAX_SIZE = 1_200_000_000L

    fun import(context: Context, uri: Uri): File {
        val target = File(context.filesDir, "imported-" + System.currentTimeMillis() + ".sf2")
        val part = File(context.filesDir, target.name + ".part")
        try {
            val raw = context.contentResolver.openInputStream(uri) ?: error("無法開啟檔案")
            BufferedInputStream(raw).use { source ->
                source.mark(8)
                val signature = ByteArray(8)
                val found = source.read(signature)
                source.reset()
                when {
                    found >= 4 && signature[0] == 'P'.code.toByte() &&
                        signature[1] == 'K'.code.toByte() -> ZipInputStream(source).use { archive ->
                        var valid = false
                        while (true) {
                            val entry = archive.nextEntry ?: break
                            if (!entry.isDirectory && entry.name.endsWith(".sf2", ignoreCase = true)) {
                                copyLimited(archive, part)
                                valid = true
                                break
                            }
                            archive.closeEntry()
                        }
                        check(valid) { "ZIP 裡面找不到 SF2" }
                    }
                    found >= 6 && (signature[0].toInt() and 255) == 0xfd &&
                        signature[1] == '7'.code.toByte() &&
                        signature[2] == 'z'.code.toByte() &&
                        signature[3] == 'X'.code.toByte() &&
                        signature[4] == 'Z'.code.toByte() -> {
                        TarArchiveInputStream(XZInputStream(source)).use { archive ->
                            var valid = false
                            while (true) {
                                val entry = archive.nextEntry ?: break
                                if (entry.isFile && entry.name.endsWith(".sf2", ignoreCase = true)) {
                                    copyLimited(archive, part)
                                    valid = true
                                    break
                                }
                            }
                            check(valid) { "TAR.XZ 裡面找不到 SF2" }
                        }
                    }
                    else -> copyLimited(source, part)
                }
            }
            check(part.length() >= 128) { "SF2 檔案內容過短" }
            // RIFF 'sfbk' signature; reject accidental ZIPs, HTML downloads, etc.
            part.inputStream().use { input ->
                val head = ByteArray(12)
                check(input.read(head) == head.size &&
                    String(head, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                    String(head, 8, 4, Charsets.US_ASCII) == "sfbk") { "檔案不是有效的 SF2 (RIFF/sfbk)" }
            }
            check(part.renameTo(target)) { "無法儲存 SoundFont" }
            return target
        } catch (error: Exception) {
            part.delete()
            target.delete()
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
                check(total <= MAX_SIZE) { "SoundFont 超過 1.2 GB 安全限制" }
                output.write(buf, 0, n)
            }
        }
    }
}
