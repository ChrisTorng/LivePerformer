package tw.idv.christorng.liveperformer

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.floor

data class LoopTrackView(
    val id: Int,
    val name: String,
    val channel: Int,
    val bank: Int,
    val program: Int,
    val muted: Boolean,
    val notes: Int
)
data class LoopView(
    val bpm: Int,
    val bars: Int,
    val metronome: Boolean,
    val playing: Boolean,
    val armed: Boolean,
    val recording: Boolean,
    val selectedId: Int,
    val bar: Int,
    val beat: Int,
    val tracks: List<LoopTrackView>
)

/**
 * MIDI-only looper. The clock runs on a dedicated high-priority scheduler thread,
 * not on Compose or the audio callback. Playback dispatch is best-effort (not
 * sample-accurate); all synthesis stays in the existing native Oboe callback.
 */
class LoopController(context: Context) {
    private data class Event(val tick: Int, val type: Int, val note: Int, val velocity: Int)
    private data class Track(
        val id: Int, val channel: Int, var name: String,
        var bank: Int, var program: Int, var muted: Boolean = false,
        val events: MutableList<Event> = mutableListOf()
    )

    private val prefs = context.applicationContext.getSharedPreferences("loop-session", Context.MODE_PRIVATE)
    private val tracks = mutableListOf<Track>()
    private val scheduler = ScheduledThreadPoolExecutor(1) { command ->
        Thread(command, "LivePerformer-Loop").apply { priority = Thread.MAX_PRIORITY - 1 }
    }
    private var bpm = 120
    private var bars = 2
    private var metronome = true
    private var selectedId = 1
    private var nextId = 3
    private var playing = false
    private var absoluteTick = 0.0
    private var lastScheduled = -0.01
    private var clockNs = System.nanoTime()
    private var armedTrack: Int? = null
    private var armedAt = 0.0
    private var recordingTrack: Int? = null
    private var recordStart = 0.0
    private val heldRecordingNotes = mutableSetOf<Int>()
    private var lastPublishNs = 0L
    private var lastSaveNs = System.nanoTime()
    private var dirty = false
    private val mutableState: MutableStateFlow<LoopView>
    val state: StateFlow<LoopView> get() = mutableState

    init {
        if (!restore()) installDemo()
        mutableState = MutableStateFlow(snapshot())
        scheduler.setRemoveOnCancelPolicy(true)
        scheduler.scheduleAtFixedRate({ runCatching { tick() } }, 0, 4, TimeUnit.MILLISECONDS)
    }

    private fun installDemo() {
        tracks += Track(1, 0, "鋼琴", 0, 0)
        val drums = Track(2, 9, "示範鼓組（1 小節重複）", 128, 0)
        // One bar of eighth-note hats plus kick / snare, repeated across two bars.
        for (bar in 0 until 2) {
            val offset = bar * 4 * LoopMath.PPQ
            for (step in 0 until 8) addHit(drums, offset + step * LoopMath.PPQ / 2, 42, 62)
            for (beat in 0 until 4) addHit(drums, offset + beat * LoopMath.PPQ,
                if (beat % 2 == 0) 36 else 38, 95)
        }
        drums.events.sortBy { it.tick }
        tracks += drums
        dirty = true
    }

    private fun addHit(track: Track, tick: Int, note: Int, velocity: Int) {
        track.events += Event(tick, NativeEngine.NOTE_ON, note, velocity)
        track.events += Event(tick + LoopMath.PPQ / 8, NativeEngine.NOTE_OFF, note, 0)
    }

    @Synchronized private fun restore(): Boolean {
        return runCatching {
        val savedData = prefs.getString("data", null) ?: return@runCatching false
        val json = JSONObject(savedData)
        bpm = json.optInt("bpm", 120).coerceIn(40, 240)
        bars = json.optInt("bars", 2).coerceIn(1, 8)
        metronome = json.optBoolean("metronome", true)
        selectedId = json.optInt("selectedId", 1)
        nextId = json.optInt("nextId", 3)
        val saved = json.getJSONArray("tracks")
        val occupied = mutableSetOf<Int>()
        for (i in 0 until saved.length()) {
            val obj = saved.getJSONObject(i)
            val channel = obj.getInt("channel")
            if (channel !in 0..15 || !occupied.add(channel)) continue
            val track = Track(obj.getInt("id"), channel, obj.optString("name", "樂器"),
                obj.optInt("bank"), obj.optInt("program"), obj.optBoolean("muted"))
            val notes = obj.optJSONArray("events") ?: JSONArray()
            for (n in 0 until notes.length()) {
                val e = notes.getJSONArray(n)
                val t = e.getInt(1)
                if (t != NativeEngine.NOTE_ON && t != NativeEngine.NOTE_OFF) continue
                val tick = e.getInt(0)
                if (tick in 0 until LoopMath.loopTicks(bars) && e.getInt(2) in 0..127)
                    track.events += Event(tick, t, e.getInt(2), e.getInt(3).coerceIn(0, 127))
            }
            track.events.sortBy { it.tick }
            tracks += track
        }
        if (tracks.isEmpty()) return@runCatching false
        if (tracks.none { it.id == selectedId }) selectedId = tracks.first().id
        nextId = maxOf(nextId, (tracks.maxOfOrNull { it.id } ?: 0) + 1)
        true
        }.getOrDefault(false)
    }

    @Synchronized private fun save() {
        if (!dirty) return
        val root = JSONObject()
            .put("bpm", bpm).put("bars", bars).put("metronome", metronome)
            .put("selectedId", selectedId).put("nextId", nextId)
        val saved = JSONArray()
        for (t in tracks) {
            val obj = JSONObject().put("id", t.id).put("channel", t.channel)
                .put("name", t.name).put("bank", t.bank).put("program", t.program)
                .put("muted", t.muted)
            val events = JSONArray()
            t.events.forEach { e -> events.put(JSONArray().put(e.tick).put(e.type).put(e.note).put(e.velocity)) }
            saved.put(obj.put("events", events))
        }
        prefs.edit().putString("data", root.put("tracks", saved).toString()).apply()
        dirty = false
        lastSaveNs = System.nanoTime()
    }

    @Synchronized private fun snapshot(): LoopView {
        val length = LoopMath.loopTicks(bars)
        val at = ((absoluteTick.toLong() % length + length) % length).toInt()
        return LoopView(bpm, bars, metronome, playing, armedTrack != null,
            recordingTrack != null, selectedId, at / (4 * LoopMath.PPQ) + 1,
            (at / LoopMath.PPQ) % 4 + 1, tracks.map {
                LoopTrackView(it.id, it.name, it.channel, it.bank, it.program, it.muted,
                    it.events.count { event -> event.type == NativeEngine.NOTE_ON })
            })
    }

    private fun publish() {
        mutableState.value = snapshot()
        lastPublishNs = System.nanoTime()
    }
    private fun changed() {
        dirty = true
        publish()
        save()
    }

    private fun advance(now: Long) {
        if (playing) {
            val delta = (now - clockNs).coerceAtLeast(0)
            absoluteTick += delta.toDouble() * bpm * LoopMath.PPQ / 60_000_000_000.0
        }
        clockNs = now
    }

    private fun stopRecord(at: Double) {
        val id = recordingTrack
        if (id != null) {
            val track = tracks.firstOrNull { it.id == id }
            if (track != null) {
                val length = LoopMath.loopTicks(bars)
                val finalTick = LoopMath.recordedTick(at, recordStart, length)
                heldRecordingNotes.forEach { note ->
                    track.events += Event(finalTick, NativeEngine.NOTE_OFF, note, 0)
                }
                track.events.sortBy { it.tick }
            }
            dirty = true
        }
        heldRecordingNotes.clear()
        recordingTrack = null
        armedTrack = null
    }

    private fun updateRecording() {
        if (armedTrack != null && absoluteTick >= armedAt) {
            recordingTrack = armedTrack
            recordStart = armedAt
            armedTrack = null
            heldRecordingNotes.clear()
        }
        if (recordingTrack != null && absoluteTick >= recordStart + LoopMath.loopTicks(bars))
            stopRecord(recordStart + LoopMath.loopTicks(bars) - 1)
    }

    @Synchronized private fun tick() {
        val now = System.nanoTime()
        advance(now)
        updateRecording()
        if (playing) {
            val length = LoopMath.loopTicks(bars)
            for (track in tracks) if (!track.muted) for (e in track.events) {
                val occurrence = LoopMath.nextOccurrence(lastScheduled, e.tick, length)
                if (occurrence <= absoluteTick && occurrence > absoluteTick - length) {
                    NativeEngine.midi(e.type, track.channel, e.note, e.velocity)
                }
            }
            if (metronome) {
                val beat = LoopMath.PPQ
                val start = (floor(lastScheduled / beat) + 1) * beat
                if (start <= absoluteTick) {
                    val number = (start / beat).toLong()
                    NativeEngine.noteOn(if (number % 4L == 0L) 76 else 77, 82, 9)
                }
                val clickOff = (floor((lastScheduled - 96) / beat) + 1) * beat + 96
                if (clickOff <= absoluteTick)
                    NativeEngine.noteOff(if ((clickOff / beat).toLong() % 4L == 0L) 76 else 77, 9)
            }
            lastScheduled = absoluteTick
        }
        if (now - lastPublishNs > 90_000_000L) publish()
        if (dirty && now - lastSaveNs > 2_000_000_000L) save()
    }

    @Synchronized fun playPause() {
        val now = System.nanoTime()
        advance(now)
        playing = !playing
        if (!playing) {
            if (recordingTrack != null || armedTrack != null) stopRecord(absoluteTick)
            NativeEngine.midi(NativeEngine.ALL_SOUNDS_OFF, 0, 0, 0)
        } else {
            lastScheduled = absoluteTick - 0.01
        }
        publish()
        save()
    }

    @Synchronized fun recordPause() {
        advance(System.nanoTime())
        if (recordingTrack != null || armedTrack != null) {
            stopRecord(absoluteTick)
            changed()
            return
        }
        if (!playing) {
            absoluteTick = 0.0
            lastScheduled = -0.01
            playing = true
            clockNs = System.nanoTime()
            armedAt = 0.0
        } else armedAt = LoopMath.nextBoundary(absoluteTick, LoopMath.loopTicks(bars))
        armedTrack = selectedId
        updateRecording()
        publish()
    }

    @Synchronized fun clearSelected() {
        val t = tracks.firstOrNull { it.id == selectedId } ?: return
        if (recordingTrack == t.id || armedTrack == t.id) stopRecord(absoluteTick)
        t.events.clear()
        NativeEngine.midi(NativeEngine.CONTROLLER, t.channel, 123, 0)
        changed()
    }

    @Synchronized fun onMidi(type: Int, originalChannel: Int, a: Int, b: Int) {
        // Route physical MIDI and touch events to the selected instrument's channel.
        val selected = tracks.firstOrNull { it.id == selectedId } ?: return
        advance(System.nanoTime())
        updateRecording()
        if (type == NativeEngine.NOTE_ON || type == NativeEngine.NOTE_OFF) {
            if (recordingTrack == selected.id) {
                val relative = LoopMath.recordedTick(absoluteTick, recordStart, LoopMath.loopTicks(bars))
                selected.events += Event(relative, type, a.coerceIn(0, 127), b.coerceIn(0, 127))
                if (type == NativeEngine.NOTE_ON) heldRecordingNotes += a
                else heldRecordingNotes -= a
                dirty = true
            }
        }
        NativeEngine.midi(type, selected.channel, a, b)
    }

    @Synchronized fun setBpm(value: Int) {
        advance(System.nanoTime())
        bpm = value.coerceIn(40, 240)
        changed()
    }
    @Synchronized fun setBars(value: Int) {
        if (recordingTrack != null || armedTrack != null) stopRecord(absoluteTick)
        bars = value.coerceIn(1, 8)
        absoluteTick = 0.0
        lastScheduled = -0.01
        NativeEngine.midi(NativeEngine.ALL_SOUNDS_OFF, 0, 0, 0)
        changed()
    }
    @Synchronized fun setMetronome(value: Boolean) {
        metronome = value
        changed()
    }
    @Synchronized fun selectTrack(id: Int) {
        if (tracks.any { it.id == id }) {
            selectedId = id
            changed()
        }
    }
    @Synchronized fun addTrack(drums: Boolean = false): Boolean {
        val used = tracks.map { it.channel }.toSet()
        val channel = (if (drums) listOf(9) else (0..15).filter { it != 9 })
            .firstOrNull { it !in used } ?: return false
        val id = nextId++
        tracks += Track(id, channel, if (drums) "鼓組 $id" else "樂器 $id",
            if (drums) 128 else 0, 0)
        selectedId = id
        if (!drums) NativeEngine.selectPreset(0, 0, channel)
        changed()
        return true
    }
    @Synchronized fun removeTrack(id: Int) {
        if (tracks.size <= 1) return
        val track = tracks.firstOrNull { it.id == id } ?: return
        if (recordingTrack == id || armedTrack == id) stopRecord(absoluteTick)
        NativeEngine.midi(NativeEngine.CONTROLLER, track.channel, 123, 0)
        tracks.remove(track)
        if (selectedId == id) selectedId = tracks.first().id
        changed()
    }
    @Synchronized fun muteTrack(id: Int) {
        val track = tracks.firstOrNull { it.id == id } ?: return
        track.muted = !track.muted
        if (track.muted) NativeEngine.midi(NativeEngine.CONTROLLER, track.channel, 123, 0)
        changed()
    }
    @Synchronized fun setPreset(bank: Int, program: Int) {
        val track = tracks.firstOrNull { it.id == selectedId } ?: return
        if (track.channel == 9) return
        track.bank = bank
        track.program = program
        NativeEngine.selectPreset(bank, program, track.channel)
        changed()
    }
    @Synchronized fun refreshPrograms() {
        for (track in tracks) if (track.channel != 9)
            NativeEngine.selectPreset(track.bank, track.program, track.channel)
    }

    @Synchronized fun close() {
        if (recordingTrack != null || armedTrack != null) stopRecord(absoluteTick)
        save()
        scheduler.shutdownNow()
    }
}
