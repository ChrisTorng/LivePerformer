package tw.idv.christorng.liveperformer

/** Source identity is stable across reconnects when the device exposes manufacturer/product/name.
 * Two indistinguishable physical units intentionally share the same key until a unique ID is available. */
data class MidiEvent(
    val deviceKey: String,
    val deviceName: String,
    val deviceId: Int,
    val port: Int,
    val status: Int,
    val data1: Int = 0,
    val data2: Int = 0
) {
    val channel: Int get() = status and 15 // Zero based; show as 1..16 in the UI.
    val kind: Int get() = status and 0xf0
}

data class MidiDeviceView(
    val id: Int, val key: String, val name: String,
    val inputPorts: Int, val outputPorts: Int
)

/** A project-level rule. Track IDs refer to project tracks, not physical MIDI channels.
 * action: note (passthrough/chromatic), scale, custom, drum-pads (fixed 8 roles), controls. */
data class MidiRoute(
    val id: Int,
    val deviceKey: String = "*",
    val channel: Int = -1,
    val kind: String = "note",
    val first: Int = 0,
    val last: Int = 127,
    val trackId: Int,
    val action: String = "note",
    val noteBase: Int = -1,
    val scaleMode: String = "major",
    val noteMap: List<Int> = emptyList(),
    /** legacy keeps pre-v0.4.9 behavior; press-toggle toggles on CC >=64, ignoring release. */
    val ccMode: String = "legacy"
) {
    companion object {
        val ccModes = setOf("legacy", "press-toggle", "absolute", "momentary")
    }

    /** A momentary hardware Pad can toggle app state without affecting Note playback. */
    fun targetState(event: MidiEvent, current: Boolean): Boolean? {
        if (event.kind != 0xb0) return null
        return when (ccMode) {
            "press-toggle", "momentary" -> if (event.data2 >= 64) !current else null
            "absolute" -> event.data2 >= 64
            else -> when (action) {
                "mute", "solo", "slot-mute", "slot-solo" -> event.data2 >= 64
                else -> if (event.data2 >= 64) !current else null
            }
        }
    }

    fun matches(event: MidiEvent): Boolean =
        (deviceKey == "*" || deviceKey == event.deviceKey) &&
            (channel == -1 || channel == event.channel) &&
            (if (kind == "note") event.kind == 0x80 || event.kind == 0x90
                else if (kind == "cc") event.kind == 0xb0
                else event.kind == 0xc0) &&
            event.data1 in first..last

    /** Convert a Note/CC pad to a playable note. CC pads should be Momentary (127 press / 0 release). */
    fun noteFor(event: MidiEvent): Pair<Int, Int>? {
        if (action !in setOf("note", "scale", "custom", "drum-pads") || !matches(event)) return null
        val sourceDown = if (kind == "note") event.kind == 0x90 && event.data2 > 0
            else event.data2 >= 64
        val offset = event.data1 - first
        val pitch = when (action) {
            "custom" -> noteMap.getOrNull(offset) ?: return null
            "drum-pads" -> DrumPads.gmNotes.getOrNull(offset) ?: return null
            "scale" -> MidiPitch.scale(noteBase.coerceAtLeast(0), offset, scaleMode)
            else -> if (noteBase >= 0) noteBase + offset else event.data1
        }
        if (pitch !in 0..127) return null
        val velocity = if (sourceDown) {
            if (kind == "note") event.data2.coerceIn(1, 127) else 100
        } else 0
        return pitch to velocity
    }
}

/** MIDI numbers are always semitone-based, regardless of the CC controller number. */
object MidiPitch {
    val names = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
    val modes = listOf("major", "minor")
    fun scale(root: Int, index: Int, mode: String = "major"): Int {
        val intervals = if (mode == "minor") intArrayOf(0, 2, 3, 5, 7, 8, 10)
            else intArrayOf(0, 2, 4, 5, 7, 9, 11)
        return root + (index / 7) * 12 + intervals[index % 7]
    }
    fun name(number: Int): String =
        if (number !in 0..127) "—" else names[number % 12] + (number / 12 - 1)
}


/** Recording armed tracks and playable keyboard destinations are separate concepts. */
object InputRoutingPolicy {
    fun keyboardDestinations(
        armedIds: Set<Int>,
        routes: List<MidiRoute>,
        sourceIsMpk: Boolean,
        defaultDrumId: Int?,
        defaultBassId: Int?
    ): Set<Int> {
        val padTargets = routes.asSequence().filter {
            it.trackId != 0 && it.action in setOf("note", "scale", "custom", "drum-pads") &&
                ((it.kind == "note" && it.channel == 1 && it.first in 36..51 &&
                    it.last in 36..51) ||
                 (it.kind == "cc" && it.first in 20..35 && it.last in 20..35))
        }.map { it.trackId }.toMutableSet()
        if (sourceIsMpk) {
            if (defaultDrumId != null) padTargets.add(defaultDrumId)
            if (defaultBassId != null) padTargets.add(defaultBassId)
        }
        return armedIds - padTargets
    }
}


/**
 * The keyboard monitor observes physical NoteOn/NoteOff independently of recording
 * destinations, mute/solo, or SoundFont expression (CC11).
 * MPK's keybed uses MIDI channel 1 (zero-based channel 0); pad Note banks use 2.
 */
class MidiKeybedMonitor {
    private val bySource = mutableMapOf<String, MutableMap<Int, Int>>()

    fun accept(event: MidiEvent): Boolean {
        if (event.channel != 0 || event.kind !in setOf(0x80, 0x90)) return false
        val source = "${event.deviceKey}:${event.deviceId}:${event.port}"
        val note = event.data1
        if (note !in 0..127) return false
        val down = event.kind == 0x90 && event.data2 > 0
        if (down) {
            bySource.getOrPut(source) { mutableMapOf() }[note] =
                event.data2.coerceIn(1, 127)
        } else {
            bySource[source]?.remove(note)
            if (bySource[source]?.isEmpty() == true) bySource.remove(source)
        }
        return true
    }

    fun velocities(): Map<Int, Int> {
        val result = mutableMapOf<Int, Int>()
        bySource.values.forEach { notes ->
            notes.forEach { (note, velocity) ->
                result[note] = maxOf(result[note] ?: 0, velocity)
            }
        }
        return result
    }

    fun clear() = bySource.clear()
}


/** MPK down-joystick CC11 is 0 at rest. Passing that through as standard
 * Expression would permanently silence the synth until CC11=127 arrived. */
object JoystickExpressionPolicy {
    fun needsNeutralExpression(event: MidiEvent, hasMatchedRoute: Boolean): Boolean =
        !hasMatchedRoute && event.kind == 0xb0 && event.channel == 0 &&
            event.data1 == 11 && event.deviceName.contains("MPK", ignoreCase = true)
}
