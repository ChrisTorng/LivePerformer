package tw.idv.christorng.liveperformer

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.ConcurrentHashMap

/** Android MIDI port messages are forwarded without sending them through Compose state. */
class MidiInput(context: Context, private val onDevicesChanged: (String) -> Unit) {
    private val manager = context.getSystemService(MidiManager::class.java)
    private val thread = HandlerThread("LivePerformer-MIDI").apply { start() }
    private val handler = Handler(thread.looper)
    private val devices = ConcurrentHashMap<Int, MidiDevice>()
    private val ports = ConcurrentHashMap<Int, List<MidiOutputPort>>()
    private val opening = mutableSetOf<Int>() // accessed on handler thread
    private var pendingMsbBank = 0
    private var pendingLsbBank = 0

    private val receiver = object : MidiReceiver() {
        override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
            val end = (offset + count).coerceAtMost(data.size)
            var i = offset
            var status = 0
            while (i < end) {
                val byte = data[i].toInt() and 0xff
                if (byte >= 0xF8) { i++; continue } // MIDI real-time single-byte messages
                if (byte >= 0x80) {
                    status = byte
                    i++
                    if (status >= 0xF0) { // Ignore SysEx/system-common in this MVP.
                        if (status == 0xF0) while (i < end && (data[i].toInt() and 0xff) != 0xF7) i++
                        if (i < end && status == 0xF0) i++
                        status = 0
                        continue
                    }
                } else if (status == 0) { i++; continue }
                val kind = status and 0xF0
                val channel = status and 0x0F
                val needed = if (kind == 0xC0 || kind == 0xD0) 1 else 2
                if (i + needed > end) break
                val a = data[i++].toInt() and 0x7F
                val b = if (needed == 2) data[i++].toInt() and 0x7F else 0
                when (kind) {
                    0x80 -> NativeEngine.midi(NativeEngine.NOTE_OFF, channel, a, b)
                    0x90 -> NativeEngine.midi(if (b == 0) NativeEngine.NOTE_OFF else NativeEngine.NOTE_ON, channel, a, b)
                    0xB0 -> {
                        if (a == 0) pendingMsbBank = b
                        if (a == 32) pendingLsbBank = b
                        NativeEngine.midi(NativeEngine.CONTROLLER, channel, a, b)
                    }
                    0xC0 -> NativeEngine.midi(NativeEngine.PROGRAM, channel, a, 0)
                    0xE0 -> NativeEngine.midi(NativeEngine.PITCH_BEND, channel, 0, a or (b shl 7))
                }
            }
        }
    }

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) = open(device)
        override fun onDeviceRemoved(device: MidiDeviceInfo) {
            ports.remove(device.id)?.forEach { it.close() }
            devices.remove(device.id)?.close()
            update()
        }
    }

    init {
        manager.registerDeviceCallback(callback, handler)
        manager.devices.forEach { open(it) }
        update()
    }
    private fun open(info: MidiDeviceInfo) {
        if (info.outputPortCount < 1 || devices.containsKey(info.id) || !opening.add(info.id)) return
        manager.openDevice(info, { device ->
            opening.remove(info.id)
            if (device != null) {
                devices[info.id] = device
                ports[info.id] = (0 until info.outputPortCount).mapNotNull { port ->
                    device.openOutputPort(port)?.also { it.connect(receiver) }
                }
            }
            update()
        }, handler)
    }
    private fun update() {
        val text = if (devices.isEmpty()) "USB MIDI：尚未連接鍵盤" else
            "USB MIDI：" + devices.values.joinToString { it.info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "裝置 ${it.info.id}" }
        onDevicesChanged(text)
    }
    fun close() {
        manager.unregisterDeviceCallback(callback)
        ports.values.flatten().forEach { it.close() }
        devices.values.forEach { it.close() }
        thread.quitSafely()
    }
}
