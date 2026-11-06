package tw.idv.christorng.liveperformer

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.util.Locale

/**
 * Offline compatibility import: SF3 Ogg-Vorbis samples -> plain SF2 PCM samples.
 * No decoding occurs in the low-latency audio callback. Uses Android's built-in
 * Vorbis decoder, so no Play Store codec or external executable is required.
 *
 * The expanded SF2 may be substantially larger than the compressed SF3.
 */
internal fun interface VorbisSampleDecoder {
    /** Writes mono signed PCM16 LE samples at [target]'s current position. Returns sample frames. */
    fun decode(source: File, offset: Long, bytes: Long, target: RandomAccessFile, sampleRate: Int): Long
}

internal object Sf3ToSf2Converter {
    private const val MAX_FILE = 2_000_000_000L
    private const val SHDR_RECORD = 46
    private const val SILENCE_FRAMES = 46
    private const val COMPRESSED_FLAG = 0x10
    private data class Chunk(val id: String, val position: Long, val size: Long) {
        val data: Long get() = position + 8
        val end: Long get() = data + size
        val paddedEnd: Long get() = end + (size and 1L)
    }
    private data class Sample(
        val record: ByteArray,
        val start: Long,
        val end: Long,
        val loopStart: Long,
        val loopEnd: Long,
        val sampleRate: Int,
        val type: Int
    ) {
        val compressed: Boolean get() = (type and COMPRESSED_FLAG) != 0
    }

    private fun fourcc(raf: RandomAccessFile): String {
        val bytes = ByteArray(4)
        raf.readFully(bytes)
        return String(bytes, Charsets.US_ASCII)
    }

    private fun readU32(raf: RandomAccessFile): Long {
        val a = raf.readUnsignedByte()
        val b = raf.readUnsignedByte()
        val c = raf.readUnsignedByte()
        val d = raf.readUnsignedByte()
        return (a.toLong() or (b.toLong() shl 8) or (c.toLong() shl 16) or (d.toLong() shl 24))
    }
    private fun readU32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 255L) or
            ((bytes[offset + 1].toLong() and 255L) shl 8) or
            ((bytes[offset + 2].toLong() and 255L) shl 16) or
            ((bytes[offset + 3].toLong() and 255L) shl 24))
    private fun readU16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
    private fun putU32(bytes: ByteArray, offset: Int, value: Long) {
        require(value in 0..0xffff_ffffL)
        for (i in 0..3) bytes[offset + i] = (value ushr (8 * i)).toByte()
    }
    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }
    private fun putU32(raf: RandomAccessFile, value: Long) {
        require(value in 0..0xffff_ffffL)
        repeat(4) { i -> raf.write((value ushr (8 * i)).toInt() and 255) }
    }
    private fun putU32At(raf: RandomAccessFile, position: Long, value: Long) {
        val end = raf.filePointer
        raf.seek(position)
        putU32(raf, value)
        raf.seek(end)
    }
    private fun writeId(raf: RandomAccessFile, value: String) =
        raf.write(value.toByteArray(Charsets.US_ASCII))

    private fun chunkAt(raf: RandomAccessFile, position: Long, limit: Long): Chunk {
        require(position >= 0 && position + 8 <= limit) { "SoundFont RIFF 區塊超出檔案範圍" }
        raf.seek(position)
        val result = Chunk(fourcc(raf), position, readU32(raf))
        require(result.end <= limit && result.paddedEnd <= limit) { "SoundFont RIFF 區塊長度錯誤" }
        return result
    }
    private fun children(raf: RandomAccessFile, list: Chunk): List<Chunk> {
        require(list.id == "LIST" && list.size >= 4) { "SoundFont LIST 格式錯誤" }
        val result = mutableListOf<Chunk>()
        var pos = list.data + 4
        while (pos + 8 <= list.end) {
            val child = chunkAt(raf, pos, list.end)
            result += child
            pos = child.paddedEnd
        }
        require(pos == list.end) { "SoundFont LIST 尾端不完整" }
        return result
    }
    private fun copy(raf: RandomAccessFile, out: RandomAccessFile, offset: Long, size: Long) {
        require(size >= 0 && size <= MAX_FILE)
        raf.seek(offset)
        var remaining = size
        val buffer = ByteArray(128 * 1024)
        while (remaining > 0) {
            val count = minOf(remaining, buffer.size.toLong()).toInt()
            raf.readFully(buffer, 0, count)
            out.write(buffer, 0, count)
            remaining -= count
        }
    }

    /** Decoder injection allows deterministic RIFF/loop tests without Android codecs. */
    fun convert(
        source: File,
        destination: File,
        onProgress: (SoundFontImportProgress) -> Unit = {},
        decoder: VorbisSampleDecoder = AndroidVorbisSampleDecoder
    ) {
        require(source != destination)
        RandomAccessFile(source, "r").use { input ->
            require(input.length() in 12..MAX_FILE) { "SF3 大小不正確或超過 2 GB" }
            require(fourcc(input) == "RIFF") { "SF3 並非 RIFF 檔案" }
            val riffSize = readU32(input)
            require(riffSize + 8 <= input.length() && fourcc(input) == "sfbk") { "SF3 RIFF/sfbk 錯誤" }
            val riffEnd = riffSize + 8
            val top = mutableListOf<Chunk>()
            var cursor = 12L
            while (cursor + 8 <= riffEnd) {
                val c = chunkAt(input, cursor, riffEnd)
                top += c
                cursor = c.paddedEnd
            }
            require(cursor == riffEnd) { "SF3 主區塊長度錯誤" }
            fun listOfType(type: String): Chunk {
                val candidates = top.filter {
                    it.id == "LIST" && it.size >= 4 &&
                        run { input.seek(it.data); fourcc(input) == type }
                }
                require(candidates.size == 1) { "缺少或重複的 SF3 $type 區塊" }
                return candidates.single()
            }
            val info = listOfType("INFO")
            val sdta = listOfType("sdta")
            val pdta = listOfType("pdta")
            require(top.indexOf(sdta) < top.indexOf(pdta)) { "SF3 區塊排列不符合 SoundFont 格式" }
            val smpl = children(input, sdta).singleOrNull { it.id == "smpl" }
                ?: error("SF3 找不到 smpl 音訊資料")
            val shdr = children(input, pdta).singleOrNull { it.id == "shdr" }
                ?: error("SF3 找不到 shdr 樣本表")
            val ifil = children(input, info).singleOrNull { it.id == "ifil" }
                ?: error("SF3 找不到 ifil 版本資訊")
            require(ifil.size >= 4 && shdr.size % SHDR_RECORD == 0L &&
                shdr.size in SHDR_RECORD.toLong()..(SHDR_RECORD * 20_001L)) {
                "SF3 樣本表損壞"
            }
            val table = ByteArray(shdr.size.toInt())
            input.seek(shdr.data)
            input.readFully(table)
            val n = table.size / SHDR_RECORD - 1 // Terminal EOS record must remain last.
            val samples = (0 until n).map { index ->
                val h = table.copyOfRange(index * SHDR_RECORD, (index + 1) * SHDR_RECORD)
                Sample(h, readU32(h, 20), readU32(h, 24), readU32(h, 28),
                    readU32(h, 32), readU32(h, 36).toInt(), readU16(h, 44))
            }
            require(samples.isNotEmpty()) { "SF3 沒有可用的音色樣本" }
            destination.parentFile?.mkdirs()
            try {
                RandomAccessFile(destination, "rw").use { out ->
                    out.setLength(0)
                    writeId(out, "RIFF")
                    putU32(out, 0)
                    writeId(out, "sfbk")
                    var pcmFrames = 0L
                    var decoded = false
                    for (topChunk in top) {
                        when (topChunk) {
                            sdta -> {
                                val listStart = out.filePointer
                                writeId(out, "LIST")
                                putU32(out, 0)
                                writeId(out, "sdta")
                                writeId(out, "smpl")
                                val sizeAt = out.filePointer
                                putU32(out, 0)
                                val payloadStart = out.filePointer
                                for ((index, sample) in samples.withIndex()) {
                                    val sampleStart = pcmFrames
                                    val dataStart = out.filePointer
                                    val frames: Long
                                    if (sample.compressed) {
                                        require(sample.start < smpl.size && sample.end >= sample.start &&
                                            sample.end < smpl.size && sample.sampleRate in 4000..384000) {
                                            "SF3 第 ${index + 1} 個壓縮樣本範圍錯誤"
                                        }
                                        input.seek(smpl.data + sample.start)
                                        require(fourcc(input) == "OggS") {
                                            "SF3 第 ${index + 1} 個樣本不是 Ogg Vorbis"
                                        }
                                        // SF3 dwEnd is inclusive: the last byte of the Ogg stream.
                                        frames = decoder.decode(source, smpl.data + sample.start,
                                            sample.end - sample.start + 1, out, sample.sampleRate)
                                        decoded = true
                                    } else {
                                        require(sample.end >= sample.start &&
                                            sample.end <= smpl.size / 2) {
                                            "SF3 第 ${index + 1} 個 PCM 樣本範圍錯誤"
                                        }
                                        frames = sample.end - sample.start
                                        copy(input, out, smpl.data + sample.start * 2, frames * 2)
                                    }
                                    require(frames > 0 && (out.filePointer - dataStart) == frames * 2) {
                                        "SF3 第 ${index + 1} 個樣本解碼長度錯誤"
                                    }
                                    require(out.filePointer < MAX_FILE - 256L * 1024) {
                                        "SF3 解碼後超過 2 GB，無法匯入"
                                    }
                                    val ls = if (sample.compressed) sample.loopStart
                                        else sample.loopStart - sample.start
                                    val le = if (sample.compressed) sample.loopEnd
                                        else sample.loopEnd - sample.start
                                    val adjustedStart = if (ls >= 0 && ls < frames) ls else 0L
                                    val adjustedEnd = if (le > adjustedStart && le <= frames) le else frames
                                    val h = sample.record
                                    putU32(h, 20, sampleStart)
                                    putU32(h, 24, sampleStart + frames)
                                    putU32(h, 28, sampleStart + adjustedStart)
                                    putU32(h, 32, sampleStart + adjustedEnd)
                                    putU16(h, 44, sample.type and COMPRESSED_FLAG.inv())
                                    h.copyInto(table, index * SHDR_RECORD)
                                    out.write(ByteArray(SILENCE_FRAMES * 2))
                                    pcmFrames += frames + SILENCE_FRAMES
                                    onProgress(SoundFontImportProgress(
                                        "SF3 → SF2：樣本 ${index + 1} / $n",
                                        out.filePointer - payloadStart
                                    ))
                                }
                                val last = n * SHDR_RECORD
                                repeat(4) { offset -> putU32(table, last + 20 + offset * 4, pcmFrames) }
                                putU32At(out, sizeAt, out.filePointer - payloadStart)
                                putU32At(out, listStart + 4, out.filePointer - listStart - 8)
                            }
                            else -> {
                                val newStart = out.filePointer
                                copy(input, out, topChunk.position, topChunk.paddedEnd - topChunk.position)
                                if (topChunk == info) {
                                    val resume = out.filePointer
                                    out.seek(newStart + (ifil.data - info.position))
                                    out.write(2)  // SoundFont 2.x uncompressed PCM.
                                    out.write(0)
                                    out.seek(resume)
                                }
                                if (topChunk == pdta) {
                                    val resume = out.filePointer
                                    out.seek(newStart + (shdr.data - pdta.position))
                                    out.write(table)
                                    out.seek(resume)
                                }
                            }
                        }
                        require(out.filePointer < MAX_FILE) { "轉換後的 SoundFont 超過 2 GB" }
                    }
                    require(decoded) { "SF3 不含 Vorbis 壓縮樣本，無需轉換" }
                    putU32At(out, 4, out.length() - 8)
                    out.fd.sync()
                }
            } catch (ex: Exception) {
                destination.delete()
                throw ex
            }
        }
    }
}

/** Android platform decoder; runs only during import on Dispatchers.IO. */
internal object AndroidVorbisSampleDecoder : VorbisSampleDecoder {
    override fun decode(
        source: File, offset: Long, bytes: Long, target: RandomAccessFile, sampleRate: Int
    ): Long {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            FileInputStream(source).use { input ->
                extractor.setDataSource(input.fd, offset, bytes)
            }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "audio/vorbis"
            } ?: error("Android 無法辨識 SF3 內的 Ogg Vorbis 樣本")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            require(format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1) {
                "SF3 Vorbis 樣本必須為單聲道"
            }
            require(format.getInteger(MediaFormat.KEY_SAMPLE_RATE) == sampleRate) {
                "SF3 Vorbis 樣本取樣率不一致"
            }
            val decoder = MediaCodec.createDecoderByType("audio/vorbis")
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            var inputDone = false
            var outputDone = false
            var consecutiveIdle = 0
            val info = MediaCodec.BufferInfo()
            var frames = 0L
            while (!outputDone) {
                var madeProgress = false
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index) ?: error("Vorbis 解碼器輸入失敗")
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size,
                                extractor.sampleTime.coerceAtLeast(0), 0)
                            extractor.advance()
                        }
                        madeProgress = true
                    }
                }
                when (val index = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> madeProgress = true
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {}
                    else -> if (index >= 0) {
                        if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            val buffer = decoder.getOutputBuffer(index)
                                ?: error("Vorbis 解碼器輸出失敗")
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val outputFormat = decoder.outputFormat
                            require(outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1) {
                                "解碼後的 Vorbis 樣本不是單聲道"
                            }
                            val pcmEncoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING))
                                outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            else AudioFormat.ENCODING_PCM_16BIT
                            if (pcmEncoding == AudioFormat.ENCODING_PCM_16BIT) {
                                require(info.size % 2 == 0)
                                val raw = ByteArray(info.size)
                                buffer.get(raw)
                                target.write(raw)
                                frames += raw.size / 2L
                            } else if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                require(info.size % 4 == 0)
                                buffer.order(ByteOrder.LITTLE_ENDIAN)
                                val raw = ByteArray(info.size / 2)
                                var pos = 0
                                while (buffer.remaining() >= 4) {
                                    val sample = (buffer.float.coerceIn(-1f, 1f) * 32767f).toInt()
                                    raw[pos++] = sample.toByte()
                                    raw[pos++] = (sample shr 8).toByte()
                                }
                                target.write(raw)
                                frames += raw.size / 2L
                            } else error("不支援的 Android PCM 解碼格式：$pcmEncoding")
                        }
                        outputDone = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        decoder.releaseOutputBuffer(index, false)
                        madeProgress = true
                    }
                }
                consecutiveIdle = if (madeProgress) 0 else consecutiveIdle + 1
                check(consecutiveIdle < 2500) { "Vorbis 解碼器無回應" }
            }
            require(frames > 0) { "Vorbis 沒有解出有效的音訊樣本" }
            return frames
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}
