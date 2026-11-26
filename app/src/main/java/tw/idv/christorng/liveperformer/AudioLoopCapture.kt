package tw.idv.christorng.liveperformer

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

/** Mono PCM, independent of MIDI quantization. All recording I/O is off the output callback. */
internal object LoopPcm {
    const val RATE = 48000
    const val MAX_SAMPLES = RATE * 48
    fun encode(pcm: ShortArray): String {
        val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asShortBuffer().put(pcm)
        return Base64.encodeToString(bytes.array(), Base64.NO_WRAP)
    }
    fun decode(encoded: String): ShortArray {
        require(encoded.length <= (MAX_SAMPLES * 2 + 2) / 3 * 4) { "錄音資料過大" }
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        require(bytes.size % 2 == 0 && bytes.size <= MAX_SAMPLES * 2) { "錄音格式錯誤" }
        return ShortArray(bytes.size / 2).also {
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it)
        }
    }
    fun peaks(pcm: ShortArray): List<Float> {
        if (pcm.isEmpty()) return emptyList()
        return List(128) { bin ->
            val from = bin * pcm.size / 128
            val end = (bin + 1) * pcm.size / 128
            var peak = 0
            for (i in from until end) peak = maxOf(peak, abs(pcm[i].toInt()))
            peak / 32768f
        }
    }
}

/** Arm opens the microphone before the count-in ends. Capture is indexed by monotonic time. */
internal class AudioLoopCapture(private val device: AudioDeviceInfo? = null) {
    @Volatile private var running = false
    @Volatile var error: String? = null
        private set
    @Volatile var peak = 0f
        private set
    @Volatile var monitor = false
    @Volatile var timestampReliable = false
        private set
    @Volatile var routedName = ""
        private set
    private val history = ShortArray(LoopPcm.RATE)
    private var historyEndNs = 0L
    private var historySize = 0
    private var historyPos = 0
    private var epoch = 0L
    private var pcm = ShortArray(0)
    private var written = 0
    private val bins = FloatArray(128)
    private var worker: Thread? = null
    @SuppressLint("MissingPermission")
    fun open() {
        running = true
        worker = Thread({
            var recorder: AudioRecord? = null
            try {
                val min = AudioRecord.getMinBufferSize(LoopPcm.RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                require(min > 0) { "不支援 48 kHz 麥克風輸入" }
                val input = AudioRecord(MediaRecorder.AudioSource.UNPROCESSED,
                    LoopPcm.RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 4096))
                recorder = input
                require(input.state == AudioRecord.STATE_INITIALIZED) { "無法開啟麥克風" }
                if (!running) return@Thread
                if (device != null) require(input.setPreferredDevice(device)) { "無法指定輸入裝置" }
                input.startRecording()
                require(input.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "無法開始錄音" }
                val buffer = ShortArray(480)
                val stamp = AudioTimestamp()
                var frames = 0L
                while (running) {
                    val count = input.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (!running) break
                    if (count <= 0) throw IllegalStateException("麥克風讀取失敗：$count")
                    val end = System.nanoTime()
                    timestampReliable = input.getTimestamp(stamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS && stamp.nanoTime > 0L
                    val start = if (timestampReliable)
                        stamp.nanoTime + (frames - stamp.framePosition) * 1000000000L / LoopPcm.RATE
                    else end - count * 1000000000L / LoopPcm.RATE
                    val actual = input.routedDevice
                    routedName = actual?.productName?.toString().orEmpty()
                    if (device != null && actual != null && actual.id != device.id) throw IllegalStateException("指定輸入裝置未成功路由")
                    if (running && monitor) NativeEngine.pushMonitorPcm(buffer, count)
                    frames += count
                    append(buffer, count, start)
                }
            } catch (e: Exception) {
                if (running) error = e.message ?: "錄音失敗"
            } finally {
                runCatching { recorder?.stop() }
                recorder?.release()
                running = false
            }
        }, "audio-loop-input").also { it.start() }
    }
    @Synchronized fun begin(start: Long, samples: Int) {
        epoch = start
        pcm = ShortArray(samples.coerceIn(1, LoopPcm.MAX_SAMPLES))
        written = 0
        bins.fill(0f)
        if (historySize > 0) {
            val prior = ShortArray(historySize) { history[(historyPos - historySize + it + history.size) % history.size] }
            place(prior, prior.size, historyEndNs - prior.size * 1000000000L / LoopPcm.RATE)
        }
    }
    @Synchronized private fun append(buffer: ShortArray, count: Int, start: Long) {
        if (!running) return
        var max = 0
        for (i in 0 until count) max = maxOf(max, abs(buffer[i].toInt()))
        peak = max / 32768f
        if (historyEndNs != 0L && kotlin.math.abs(start - historyEndNs) > 100_000_000L) historySize = 0
        for (i in 0 until count) {
            history[historyPos] = buffer[i]
            historyPos = (historyPos + 1) % history.size
        }
        historySize = minOf(history.size, historySize + count)
        historyEndNs = start + count * 1000000000L / LoopPcm.RATE
        place(buffer, count, start)
    }
    private fun place(buffer: ShortArray, count: Int, start: Long) {
        if (epoch == 0L) return
        val offset = ((start - epoch) / 1e9 * LoopPcm.RATE).roundToInt()
        val source = maxOf(0, -offset)
        val dest = maxOf(0, offset)
        val size = minOf(count - source, pcm.size - dest)
        if (size > 0) {
            buffer.copyInto(pcm, dest, source, source + size)
            for (i in dest until dest + size) {
                val bin = (i.toLong() * bins.size / pcm.size).toInt().coerceAtMost(bins.lastIndex)
                bins[bin] = maxOf(bins[bin], abs(pcm[i].toInt()) / 32768f)
            }
            written = maxOf(written, dest + size)
        }
    }
    @Synchronized fun preview(): List<Float> = bins.toList()
    @Synchronized fun finish(): ShortArray? {
        val result = if (written > 0 && error == null) pcm.copyOf(written) else null
        pcm = ShortArray(0)
        epoch = 0
        return result
    }
    @Synchronized fun discardTake() { pcm = ShortArray(0); epoch = 0; written = 0 }
    fun cancel() {
        monitor = false
        running = false
        discardTake()
        // Never wait while holding this capture's lock: reader may be appending.
        runCatching { worker?.join(100) }
    }
}
