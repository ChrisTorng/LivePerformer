package tw.idv.christorng.liveperformer

/** Render on the main/UI thread. Raw MIDI data remains unmodified for routing and recording. */
object MidiDiagnostics {
    private val pitchClasses = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
    private fun noteName(note: Int) =
        if (note in 0..127) pitchClasses[note % 12] + (note / 12 - 1) else "?"

    fun format(e: MidiEvent): String {
        val ch = if (e.status < 0xf0) " Ch " + (e.channel + 1) else ""
        val detail = when (e.kind) {
            0x80 -> "Note Off " + e.data1 + " " + noteName(e.data1) + " vel " + e.data2
            0x90 -> (if (e.data2 == 0) "Note Off " else "Note On ") +
                e.data1 + " " + noteName(e.data1) + " vel " + e.data2
            0xa0 -> "Poly Pressure note " + e.data1 + " val " + e.data2
            0xb0 -> "CC " + e.data1 + " = " + e.data2
            0xc0 -> "Program Change " + e.data1 + " (1-based " + (e.data1 + 1) + ")"
            0xd0 -> "Channel Pressure = " + e.data1
            0xe0 -> "Pitch Bend " + (e.data1 or (e.data2 shl 7)) + " (center 8192)"
            else -> when (e.status) {
                0xf8 -> "Clock"
                0xfa -> "Start"
                0xfb -> "Continue"
                0xfc -> "Stop"
                0xfe -> "Active Sensing"
                else -> "Status 0x" + e.status.toString(16).uppercase()
            }
        }
        return (if (e.port == 0) "" else "Port ${e.port + 1} · ") +
            ch.trimStart() + " · " + detail
    }
}
