package tw.idv.christorng.liveperformer

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.ConcurrentHashMap

/** Per-port stateful MIDI 1.0 decoder. Running status and split Android MIDI packets are supported. */
class MidiStreamDecoder(private val emit: (Int, Int, Int) -> Unit) {
    private var runningStatus = 0
    private var status = 0
    private var expected = 0
    private var count = 0
    private var first = 0
    private var inSysex = false

    fun accept(data: ByteArray, offset: Int, length: Int) {
        val end = (offset + length).coerceAtMost(data.size)
        for (i in offset until end) {
            val b = data[i].toInt() and 255
            if (b >= 0xf8) { emit(b, 0, 0); continue }
            if (inSysex) { if (b == 0xf7) inSysex = false; continue }
            if (b >= 0x80) {
                if (b == 0xf0) {
                    inSysex = true; runningStatus = 0; expected = 0; count = 0
                } else if (b >= 0xf0) {
                    runningStatus = 0
                    status = b
                    expected = when (b) { 0xf1, 0xf3 -> 1; 0xf2 -> 2; else -> 0 }
                    count = 0
                } else {
                    runningStatus = b
                    status = b
                    expected = if ((b and 0xf0) == 0xc0 || (b and 0xf0) == 0xd0) 1 else 2
                    count = 0
                }
                continue
            }
            if (expected == 0) {
                if (runningStatus == 0) continue
                status = runningStatus
                expected = if ((status and 0xf0) == 0xc0 || (status and 0xf0) == 0xd0) 1 else 2
                count = 0
            }
            if (count++ == 0) first = b
            if (count >= expected) {
                if (status < 0xf0) emit(status, first, if (expected == 2) b else 0)
                count = 0
                expected = 0
            }
        }
    }
}

/** Physical input/clock-output ports are independent of the active performance track. */
class MidiInput(
    context: Context,
    private val onDevicesChanged: (List<MidiDeviceView>) -> Unit,
    private val onMidi: (MidiEvent) -> Unit
) {
    private val manager = context.getSystemService(MidiManager::class.java)
    private val thread = HandlerThread("LivePerformer-MIDI").apply { start() }
    private val handler = Handler(thread.looper)
    private val devices = ConcurrentHashMap<Int, MidiDevice>()
    private val receivePorts = ConcurrentHashMap<Int, List<MidiOutputPort>>()
    private val sendPorts = ConcurrentHashMap<Int, List<MidiInputPort>>()
    private val opening = mutableSetOf<Int>()
    @Volatile private var clockTargetId: Int? = null

    private fun describe(info: MidiDeviceInfo): MidiDeviceView {
        val p = info.properties
        val name = p.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: p.getString(MidiDeviceInfo.PROPERTY_PRODUCT) ?: "MIDI 裝置"
        val maker = p.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER).orEmpty()
        val product = p.getString(MidiDeviceInfo.PROPERTY_PRODUCT).orEmpty()
        val serial = p.getString("serial_number").orEmpty()
        return MidiDeviceView(info.id, listOf(maker, product, name, serial)
            .joinToString("|"), name, info.inputPortCount, info.outputPortCount)
    }

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) = open(device)
        override fun onDeviceRemoved(device: MidiDeviceInfo) {
            receivePorts.remove(device.id)?.forEach { runCatching { it.close() } }
            sendPorts.remove(device.id)?.forEach { runCatching { it.close() } }
            devices.remove(device.id)?.close()
            if (clockTargetId == device.id) clockTargetId = null
            update()
        }
    }

    init {
        manager.registerDeviceCallback(callback, handler)
        manager.devices.forEach { open(it) }
        update()
    }

    private fun open(info: MidiDeviceInfo) {
        if (info.outputPortCount == 0 && info.inputPortCount == 0) return
        if (devices.containsKey(info.id) || !opening.add(info.id)) return
        manager.openDevice(info, { device ->
            opening.remove(info.id)
            if (device != null) {
                val identity = describe(info)
                devices[info.id] = device
                receivePorts[info.id] = (0 until info.outputPortCount).mapNotNull { port ->
                    val decoder = MidiStreamDecoder { status, a, b ->
                        onMidi(MidiEvent(identity.key, identity.name, info.id, port, status, a, b))
                    }
                    device.openOutputPort(port)?.also { output ->
                        output.connect(object : MidiReceiver() {
                            override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
                                decoder.accept(data, offset, count)
                            }
                        })
                    }
                }
                sendPorts[info.id] = (0 until info.inputPortCount).mapNotNull {
                    device.openInputPort(it)
                }
            }
            update()
        }, handler)
    }

    private fun update() {
        onDevicesChanged(devices.values.map { describe(it.info) }.sortedBy { it.name })
    }

    fun setClockTarget(id: Int?) { clockTargetId = id }

    /** 0xF8 Clock, 0xFA Start, 0xFB Continue, 0xFC Stop. Called on looper thread, never audio. */
    fun sendClock(status: Int) {
        val id = clockTargetId ?: return
        val ports = sendPorts[id] ?: return
        val bytes = byteArrayOf(status.toByte())
        // MIDI input port may be disconnected mid-send; do not block/synchronize the audio callback.
        ports.forEach { runCatching { it.send(bytes, 0, 1) } }
    }

    fun close() {
        manager.unregisterDeviceCallback(callback)
        receivePorts.values.flatten().forEach { runCatching { it.close() } }
        sendPorts.values.flatten().forEach { runCatching { it.close() } }
        devices.values.forEach { runCatching { it.close() } }
        thread.quitSafely()
    }
}
