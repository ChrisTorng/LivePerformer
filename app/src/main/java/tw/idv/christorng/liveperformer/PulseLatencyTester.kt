package tw.idv.christorng.liveperformer

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import java.util.Locale

/** Estimates the time from a native output callback to microphone capture
 * using a matched chirp. It is NOT physical MIDI-key-to-speaker latency. */
object PulseLatencyTester {
    private const val RATE = 48000
    private const val CHIRP = RATE / 50
    private const val CAPTURE = RATE * 2

    /** Uses valid microphone detections only. Failed captures are not treated as zero latency. */
    fun summarize(measurements: List<String>, xrunBefore: Int, xrunAfter: Int): String {
        val pattern = Regex("輸出 callback → 麥克風捕捉：([0-9]+(?:\\.[0-9]+)?) ms")
        val confidence = Regex("相關性 ([0-9]+(?:\\.[0-9]+)?)")
        val valid = measurements.mapNotNull { result ->
            pattern.find(result)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        }.sorted()
        val xrun = if (xrunBefore >= 0 && xrunAfter >= 0)
            "xRuns：$xrunBefore → $xrunAfter（增加 ${xrunAfter - xrunBefore}）"
        else "xRuns：裝置未提供"
        if (valid.isEmpty()) {
            val reason = measurements.firstOrNull() ?: "沒有有效結果"
            return "有效 0 / ${measurements.size} 次。$xrun\n$reason"
        }
        val mean = valid.average()
        val median = valid[valid.size / 2]
        val p95 = valid[kotlin.math.ceil(valid.size * 0.95).toInt().coerceIn(1, valid.size) - 1]
        val confidenceValues = measurements.mapNotNull {
            confidence.find(it)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        }
        fun f(v: Double) = String.format(Locale.US, "%.1f", v)
        val confidenceText = if (confidenceValues.isEmpty()) "" else
            " · 平均相關性 " + String.format(Locale.US, "%.2f", confidenceValues.average())
        return "有效 ${valid.size} / ${measurements.size} 次 · callback → 麥克風\n" +
            "平均 ${f(mean)} ms · 中位數 ${f(median)} ms\n" +
            "最小 ${f(valid.first())} · P95 ${f(p95)} · 最大 ${f(valid.last())} ms\n" +
            "$xrun$confidenceText\n" +
            "不是實體 MIDI 按鍵到耳機延遲；3–5 次的 P95 僅供初步參考。"
    }

    fun measure(): String {
        val fmt = AudioFormat.Builder().setSampleRate(RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
        val minimum = AudioRecord.getMinBufferSize(RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) return "錄音設備不支援 48 kHz PCM16。"
        var recorder: AudioRecord? = null
        var sourceName = "UNPROCESSED"
        try {
            for ((source, label) in listOf(
                MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED",
                MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION"
            )) {
                try {
                    val item = AudioRecord.Builder().setAudioSource(source)
                        .setAudioFormat(fmt)
                        .setBufferSizeInBytes(max(minimum * 4, 32768)).build()
                    if (item.state == AudioRecord.STATE_INITIALIZED) {
                        recorder = item
                        sourceName = label
                        break
                    }
                    item.release()
                } catch (_: Exception) { }
            }
            val input = recorder ?: return "麥克風無法啟動，請確認權限。"
            val pcm = ShortArray(CAPTURE)
            val block = ShortArray(512)
            input.startRecording()
            if (input.recordingState != AudioRecord.RECORDSTATE_RECORDING)
                return "錄音啟動失敗（裝置可能被其他 App 佔用）。"
            var count = 0
            var fired = false
            while (count < pcm.size) {
                val n = input.read(block, 0, minOf(block.size, pcm.size - count),
                    AudioRecord.READ_BLOCKING)
                if (n <= 0) return "麥克風讀取失敗（錯誤 " + n + "）。"
                block.copyInto(pcm, count, 0, n)
                count += n
                if (!fired && count > RATE / 3) {
                    NativeEngine.midi(NativeEngine.TEST_PULSE, 0, 0, 0)
                    fired = true
                }
            }
            val routedInput = input.routedDevice
            val routedInputText = routedInput?.let {
                "輸入路徑 ${it.productName}（ID ${it.id}，類型 ${it.type}）"
            } ?: "輸入路徑：系統未回報"
            val time = AudioTimestamp()
            val timeOk = input.getTimestamp(time, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
            val emittedNs = NativeEngine.lastPulseNs()
            if (emittedNs <= 0L) return "尚未取得音訊輸出 callback 時間。"
            val reference = DoubleArray(CHIRP) { sample ->
                val t = sample.toDouble() / RATE
                val edge = sin(PI * sample / CHIRP)
                0.24 * edge * edge * sin(2.0 * PI *
                    (600.0 * t + 0.5 * 4000.0 * t * t / (CHIRP.toDouble() / RATE)))
            }
            val e = (0 until CHIRP step 4).sumOf { reference[it] * reference[it] }
            var peak = 0.0
            var index = -1
            // Fractional normalization tolerates different microphone gain levels.
            for (start in RATE / 4 until count - CHIRP) {
                var dot = 0.0
                var energy = 0.0
                for (i in 0 until CHIRP step 4) {
                    val v = pcm[start + i] / 32768.0
                    dot += v * reference[i]
                    energy += v * v
                }
                val score = if (energy < 1e-8) 0.0 else abs(dot) / sqrt(e * energy)
                if (score > peak) {
                    peak = score
                    index = start
                }
            }
            val confidence = String.format(Locale.US, "%.2f", peak)
            if (index < 0 || peak < 0.22) {
                return "沒有偵測到回錄測試音。相關性 " + confidence +
                    "。請用喇叭播放並讓麥克風收到聲音，或使用有線 Loopback。來源：" + sourceName
            }
            if (!timeOk || time.nanoTime <= 0L) {
                return "找到回錄脈衝（相關性 " + confidence +
                    "），但系統沒有提供可靠的錄音時間戳，無法計算時間差。"
            }
            val capturedNs = time.nanoTime +
                (index.toLong() - time.framePosition) * 1_000_000_000L / RATE
            val diffMs = (capturedNs - emittedNs) / 1_000_000.0
            if (diffMs !in 0.0..1500.0)
                return "音訊時間戳不一致（" + String.format(Locale.US, "%.1f", diffMs) +
                    " ms），結果無效。相關性 " + confidence
            return "輸出 callback → 麥克風捕捉：" +
                String.format(Locale.US, "%.1f", diffMs) + " ms\n相關性 " + confidence +
                " · 錄音來源 " + sourceName +
                "\n" + routedInputText +
                "\n包含輸出與收音路徑，不包含實體 MIDI 按鍵，也不是完整 Round Trip。"
        } catch (ex: SecurityException) {
            return "沒有麥克風權限：" + ex.message
        } catch (ex: Exception) {
            return "量測失敗：" + ex.javaClass.simpleName + ": " + ex.message
        } finally {
            try { recorder?.stop() } catch (_: Exception) { }
            recorder?.release()
        }
    }
}
