package tw.idv.christorng.liveperformer

object NativeEngine {
    init { System.loadLibrary("liveperformer") }
    external fun start(): Boolean
    external fun stop()
    external fun loadSf2(path: String): Boolean
    external fun collectRetired()
    external fun midi(type: Int, channel: Int, a: Int, b: Int)
    external fun status(): String
    external fun lastPulseNs(): Long

    const val NOTE_ON = 0
    const val NOTE_OFF = 1
    const val CONTROLLER = 2
    const val PITCH_BEND = 3
    const val PROGRAM = 4
    const val TEST_PULSE = 5
    const val ALL_SOUNDS_OFF = 6

    fun noteOn(note: Int, velocity: Int = 100, channel: Int = 0) = midi(NOTE_ON, channel, note, velocity)
    fun noteOff(note: Int, channel: Int = 0) = midi(NOTE_OFF, channel, note, 0)
    fun program(number: Int) = midi(PROGRAM, 0, number, 0)
}
