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
    val noteMap: List<Int> = emptyList()
) {
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
