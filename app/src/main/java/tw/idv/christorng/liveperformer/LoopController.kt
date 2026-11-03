package tw.idv.christorng.liveperformer

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.floor

data class LoopNoteView(val start: Float, val length: Float, val pitch: Int, val velocity: Int)

data class LoopTrackView(
    val id: Int,
    val name: String,
    val channel: Int,
    val bank: Int,
    val program: Int,
    val muted: Boolean,
    val solo: Boolean,
    val notes: Int,
    val fontId: String,
    val category: String,
    val noteRects: List<LoopNoteView>
)
data class LoopView(
    val bpm: Int,
    val bars: Int,
    val beatsPerBar: Int,
    val beatUnit: Int,
    val metronome: Boolean,
    val playing: Boolean,
    val armed: Boolean,
    val recording: Boolean,
    val selectedId: Int,
    val bar: Int,
    val beat: Int,
    val progress: Float,
    val keyboardNotes: Set<Int>,
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
        var fontId: String = "gm",
        var category: String = "",
        var solo: Boolean = false,
        val events: MutableList<Event> = mutableListOf()
    )

    private val prefs = context.applicationContext.getSharedPreferences("loop-session", Context.MODE_PRIVATE)
    private val tracks = mutableListOf<Track>()
    private val fontIndexes = mutableMapOf("gm" to 0)
    private val keysDown = mutableMapOf<Int, MutableSet<Int>>()
    private val scheduler = ScheduledThreadPoolExecutor(1) { command ->
        Thread(command, "LivePerformer-Loop").apply { priority = Thread.MAX_PRIORITY - 1 }
    }
    private var bpm = 120
    private var bars = 2
    private var beatsPerBar = 4
    private var beatUnit = 4
    private var hasUnsavedChanges = true
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

    /** Two-bar C major -> A minor groove. The second bar has different drums and bass. */
    private fun installDemo() {
        bpm = 120
        bars = 2
        beatsPerBar = 4
        beatUnit = 4
        metronome = true
        nextId = 6
        selectedId = 5
        val drums = Track(1, 9, "鼓組", 128, 0)
        val bass = Track(2, 1, "Bass", 0, 33)
        val strings = Track(3, 2, "弦樂", 0, 48)
        val lead = Track(4, 3, "Lead", 0, 80)
        val piano = Track(5, 0, "鋼琴", 0, 0)
        val q = LoopMath.PPQ
        // Kick/snare variations, closed hats and the turnaround open hat.
        for (step in 0 until 16) {
            hit(drums, step * q / 2, if (step == 15) 46 else 42,
                if (step % 2 == 0) 75 else 52, q / 5)
        }
        for (beat in intArrayOf(0, 2, 4, 6)) hit(drums, beat * q, 36, 108)
        for (beat in intArrayOf(1, 3, 5, 7)) hit(drums, beat * q, 38, 105)
        hit(drums, 7 * q + q / 2, 38, 68, q / 6)
        hit(drums, 7 * q + 3 * q / 4, 38, 82, q / 6)
        // Two distinctly phrased bass bars: C2-G2 and A1-E2.
        listOf(36,36,43,40, 33,33,40,43).forEachIndexed { beat, note ->
            hit(bass, beat * q, note, 95, q * 3 / 4)
        }
        hit(bass, 3 * q + q / 2, 43, 79, q / 3)
        hit(bass, 7 * q + q / 2, 40, 78, q / 3)
        // Held C major and A minor chords, slightly overlapping the next beat.
        for (note in intArrayOf(60,64,67)) hit(strings, 0, note, 65, 4 * q - q / 8)
        for (note in intArrayOf(57,60,64)) hit(strings, 4 * q, note, 67, 4 * q - q / 8)
        // Two-bar melody, leaving the keyboard track empty for the performer.
        listOf(72,74,76,79,81,79,76,74).forEachIndexed { beat, note ->
            hit(lead, beat * q, note, 82, if (beat == 3 || beat == 7) q * 3 / 4 else q / 2)
        }
        tracks.clear()
        tracks.addAll(listOf(drums,bass,strings,lead,piano))
        listOf("鼓組","貝斯","弦樂","Lead","鋼琴").forEachIndexed { i, name ->
            tracks[i].category = name
        }
        tracks.forEach { it.events.sortBy { event -> event.tick } }
        dirty = true
    }

    private fun hit(track: Track, tick: Int, note: Int, velocity: Int, duration: Int = LoopMath.PPQ / 8) {
        track.events += Event(tick, NativeEngine.NOTE_ON, note, velocity)
        track.events += Event((tick + duration).coerceAtMost(LoopMath.loopTicks(2) - 1),
            NativeEngine.NOTE_OFF, note, 0)
    }

    @Synchronized fun loadDemo() {
        playing = false
        armedTrack = null
        recordingTrack = null
        heldRecordingNotes.clear()
        keysDown.clear()
        NativeEngine.midi(NativeEngine.ALL_SOUNDS_OFF, 0, 0, 0)
        absoluteTick = 0.0
        lastScheduled = -0.01
        clockNs = System.nanoTime()
        installDemo()
        refreshPrograms()
        changed()
    }

    @Synchronized private fun restore(): Boolean = runCatching {
        decode(prefs.getString("data", null) ?: return@runCatching false, false)
    }.getOrDefault(false)

    private fun decode(data: String, importing: Boolean): Boolean {
        val root = JSONObject(data)
        if (importing) require(root.optString("format") == "liveperformer.v1") {
            "不支援的專案檔格式"
        }
        val settingsOnly = root.optString("type") == "settings"
        val newBars = root.optInt("bars", 2).coerceIn(1, 8)
        val newNumerator = root.optInt("beatsPerBar", 4)
        val newDenominator = root.optInt("beatUnit", 4)
        require((newNumerator to newDenominator) in setOf(
            2 to 4, 3 to 4, 4 to 4, 6 to 8
        )) { "不支援的拍號" }
        val newTracks = mutableListOf<Track>()
        val occupied = mutableSetOf<Int>()
        val saved = root.getJSONArray("tracks")
        require(saved.length() in 1..16) { "樂器軌數量不正確" }
        for (i in 0 until saved.length()) {
            val obj = saved.getJSONObject(i)
            val channel = obj.getInt("channel")
            require(channel in 0..15 && occupied.add(channel)) { "MIDI 聲道重複或無效" }
            val id = obj.getInt("id")
            val track = Track(id, channel, obj.optString("name", "樂器"),
                obj.optInt("bank"), obj.optInt("program").coerceIn(0, 127),
                obj.optBoolean("muted"), obj.optString("fontId", "gm").ifBlank { "gm" },
                obj.optString("category", "").ifBlank {
                    if (channel == 9) "鼓組" else GmCategories.names[GmCategories.family(obj.optInt("program"))]
                }, obj.optBoolean("solo", false))
            if (!settingsOnly) {
                val notes = obj.optJSONArray("events") ?: JSONArray()
                require(notes.length() <= 100000) { "MIDI 音符數量過多" }
                for (n in 0 until notes.length()) {
                    val e = notes.getJSONArray(n)
                    val type = e.getInt(1)
                    val time = e.getInt(0)
                    val note = e.getInt(2)
                    if (type in NativeEngine.NOTE_ON..NativeEngine.NOTE_OFF
                        && time in 0 until LoopMath.loopTicks(newBars, newNumerator, newDenominator)
                        && note in 0..127) {
                        track.events += Event(time, type, note, e.getInt(3).coerceIn(0, 127))
                    }
                }
            } else {
                tracks.firstOrNull { it.id == id && it.channel == channel }?.let { old ->
                    track.events.addAll(old.events)
                }
            }
            track.events.sortBy { it.tick }
            newTracks += track
        }
        if (importing) {
            playing = false
            NativeEngine.midi(NativeEngine.ALL_SOUNDS_OFF, 0, 0, 0)
            armedTrack = null
            recordingTrack = null
            keysDown.clear()
            absoluteTick = 0.0
            lastScheduled = -0.01
            clockNs = System.nanoTime()
        }
        bars = newBars
        beatsPerBar = newNumerator
        beatUnit = newDenominator
        bpm = root.optInt("bpm", 120).coerceIn(40, 240)
        metronome = root.optBoolean("metronome", true)
        selectedId = root.optInt("selectedId", newTracks.first().id)
        nextId = maxOf(root.optInt("nextId", 1), newTracks.maxOf { it.id } + 1)
        tracks.clear()
        tracks.addAll(newTracks)
        if (tracks.none { it.id == selectedId }) selectedId = tracks.first().id
        if (importing) {
            refreshPrograms()
            changed()
            hasUnsavedChanges = false
        }
        return true
    }

    @Synchronized fun importDocument(content: String) {
        decode(content, true)
    }

    @Synchronized fun exportDocument(settingsOnly: Boolean): String =
        serialize(settingsOnly, true).toString()

    @Synchronized fun needsSaveBeforeReplace(): Boolean = hasUnsavedChanges

    @Synchronized fun markSavedIfUnchanged(exportedContent: String) {
        if (exportDocument(false) == exportedContent) hasUnsavedChanges = false
    }


    private fun serialize(settingsOnly: Boolean, fileFormat: Boolean): JSONObject {
        val root = JSONObject()
            .put("bpm", bpm).put("bars", bars)
            .put("beatsPerBar", beatsPerBar).put("beatUnit", beatUnit)
            .put("metronome", metronome)
            .put("selectedId", selectedId).put("nextId", nextId)
        if (fileFormat) {
            root.put("format", "liveperformer.v1")
            root.put("type", if (settingsOnly) "settings" else "project")
        }
        val saved = JSONArray()
        for (t in tracks) {
            val obj = JSONObject().put("id", t.id).put("channel", t.channel)
                .put("name", t.name).put("bank", t.bank).put("program", t.program)
                .put("muted", t.muted).put("solo", t.solo)
                .put("fontId", t.fontId).put("category", t.category)
            if (!settingsOnly) {
                val events = JSONArray()
                t.events.forEach { e ->
                    events.put(JSONArray().put(e.tick).put(e.type).put(e.note).put(e.velocity))
                }
                obj.put("events", events)
            }
            saved.put(obj)
        }
        return root.put("tracks", saved)
    }

    @Synchronized private fun save() {
        if (!dirty) return
        prefs.edit().putString("data", serialize(false, false).toString()).apply()
        dirty = false
        lastSaveNs = System.nanoTime()
    }

    private fun pianoNotes(t: Track, length: Int): List<LoopNoteView> {
        val active = mutableMapOf<Int, Event>()
        val result = mutableListOf<LoopNoteView>()
        for (e in t.events.sortedWith(compareBy<Event> { it.tick }.thenBy { it.type })) {
            if (e.type == NativeEngine.NOTE_ON && e.velocity > 0) {
                // Repeated NoteOn terminates the preceding note.
                active.put(e.note, e)?.let { old ->
                    result += LoopNoteView(old.tick.toFloat() / length,
                        (e.tick - old.tick).coerceAtLeast(1).toFloat() / length,
                        e.note, old.velocity)
                }
            } else if (e.type == NativeEngine.NOTE_OFF || e.velocity == 0) {
                active.remove(e.note)?.let { start ->
                    result += LoopNoteView(start.tick.toFloat() / length,
                        (e.tick - start.tick).coerceAtLeast(1).toFloat() / length,
                        e.note, start.velocity)
                }
            }
        }
        active.values.forEach { e ->
            result += LoopNoteView(e.tick.toFloat() / length,
                (length - e.tick).coerceAtLeast(1).toFloat() / length, e.note, e.velocity)
        }
        return result
    }

    @Synchronized private fun snapshot(): LoopView {
        val length = LoopMath.loopTicks(bars, beatsPerBar, beatUnit)
        val at = ((absoluteTick.toLong() % length + length) % length).toInt()
        return LoopView(bpm, bars, beatsPerBar, beatUnit, metronome, playing, armedTrack != null,
            recordingTrack != null, selectedId, at / LoopMath.barTicks(beatsPerBar, beatUnit) + 1,
            (at / LoopMath.beatTicks(beatUnit)) % beatsPerBar + 1, at.toFloat() / length,
            keysDown[tracks.firstOrNull { it.id == selectedId }?.channel]?.toSet() ?: emptySet(),
            tracks.map {
                LoopTrackView(it.id, it.name, it.channel, it.bank, it.program, it.muted, it.solo,
                    it.events.count { event -> event.type == NativeEngine.NOTE_ON },
                    it.fontId, it.category.ifBlank {
                        if (it.channel == 9) "鼓組" else GmCategories.names[GmCategories.family(it.program)]
                    }, pianoNotes(it, length))
            })
    }

    private fun publish() {
        mutableState.value = snapshot()
        lastPublishNs = System.nanoTime()
    }
    private fun changed() {
        hasUnsavedChanges = true
        dirty = true
        publish()
        // Save is coalesced by the scheduler to avoid a disk write per slider tick.
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
                val length = LoopMath.loopTicks(bars, beatsPerBar, beatUnit)
                val finalTick = LoopMath.recordedTick(at, recordStart, length)
                heldRecordingNotes.forEach { note ->
                    track.events += Event(finalTick, NativeEngine.NOTE_OFF, note, 0)
                }
                track.events.sortBy { it.tick }
            }
            dirty = true
            hasUnsavedChanges = true
        }
        heldRecordingNotes.clear()
        recordingTrack = null
        armedTrack = null
    }

    private fun displayKey(channel: Int, type: Int, note: Int) {
        if (type == NativeEngine.NOTE_ON) keysDown.getOrPut(channel) { mutableSetOf() }.add(note)
        if (type == NativeEngine.NOTE_OFF) keysDown[channel]?.remove(note)
    }

    private fun updateRecording() {
        if (armedTrack != null && absoluteTick >= armedAt) {
            recordingTrack = armedTrack
            recordStart = armedAt
            armedTrack = null
            heldRecordingNotes.clear()
        }
        if (recordingTrack != null && absoluteTick >= recordStart + LoopMath.loopTicks(bars, beatsPerBar, beatUnit))
            stopRecord(recordStart + LoopMath.loopTicks(bars, beatsPerBar, beatUnit) - 1)
    }

    @Synchronized private fun tick() {
        val now = System.nanoTime()
        advance(now)
        updateRecording()
        if (playing) {
            val length = LoopMath.loopTicks(bars, beatsPerBar, beatUnit)
            val anySolo = tracks.any { it.solo }
            for (track in tracks) if (!track.muted && (!anySolo || track.solo)) for (e in track.events) {
                val occurrence = LoopMath.nextOccurrence(lastScheduled, e.tick, length)
                if (occurrence <= absoluteTick && occurrence > absoluteTick - length) {
                    NativeEngine.midi(e.type, track.channel, e.note, e.velocity)
                    displayKey(track.channel, e.type, e.note)
                }
            }
            if (metronome) {
                val beat = LoopMath.beatTicks(beatUnit)
                val start = (floor(lastScheduled / beat) + 1) * beat
                if (start <= absoluteTick) {
                    val number = (start / beat).toLong()
                    NativeEngine.noteOn(if (number % beatsPerBar == 0L) 76 else 77, 82, 9)
                }
                val clickOff = (floor((lastScheduled - 96) / beat) + 1) * beat + 96
                if (clickOff <= absoluteTick)
                    NativeEngine.noteOff(if ((clickOff / beat).toLong() % beatsPerBar == 0L) 76 else 77, 9)
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
            keysDown.clear()
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
        } else armedAt = LoopMath.nextBoundary(absoluteTick, LoopMath.loopTicks(bars, beatsPerBar, beatUnit))
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
                val relative = LoopMath.recordedTick(absoluteTick, recordStart, LoopMath.loopTicks(bars, beatsPerBar, beatUnit))
                selected.events += Event(relative, type, a.coerceIn(0, 127), b.coerceIn(0, 127))
                if (type == NativeEngine.NOTE_ON) heldRecordingNotes += a
                else heldRecordingNotes -= a
                dirty = true
                hasUnsavedChanges = true
            }
        }
        displayKey(selected.channel, type, a)
        if (!selected.muted && (tracks.none { it.solo } || selected.solo))
            NativeEngine.midi(type, selected.channel, a, b)
        if (type == NativeEngine.NOTE_ON || type == NativeEngine.NOTE_OFF) publish()
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
    @Synchronized fun setMeter(numerator: Int, denominator: Int) {
        require((numerator to denominator) in setOf(2 to 4, 3 to 4, 4 to 4, 6 to 8))
        if (beatsPerBar == numerator && beatUnit == denominator) return
        if (recordingTrack != null || armedTrack != null) stopRecord(absoluteTick)
        beatsPerBar = numerator
        beatUnit = denominator
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
        tracks.last().category = if (drums) "鼓組" else "鋼琴"
        if (!drums) NativeEngine.selectFontPreset(channel, 0, 0, 0)
        changed()
        return true
    }
    @Synchronized fun addChosenTrack(
        fontId: String, bank: Int, program: Int, category: String, presetName: String
    ): Boolean {
        val drums = category == "鼓組"
        val used = tracks.map { it.channel }.toSet()
        val channel = (if (drums) listOf(9) else (0..15).filter { it != 9 })
            .firstOrNull { it !in used } ?: return false
        val id = nextId++
        val normalized = InstrumentNaming.clean(presetName).ifBlank { category }
        val track = Track(id, channel, normalized,
            if (drums) 128 else bank, program.coerceIn(0, 127),
            fontId = fontId.takeIf { it in fontIndexes } ?: "gm",
            category = category)
        tracks += track
        selectedId = id
        NativeEngine.selectFontPreset(channel, fontIndexes[track.fontId] ?: 0, track.bank, track.program)
        changed()
        return true
    }

    @Synchronized fun moveTrack(id: Int, direction: Int) {
        val from = tracks.indexOfFirst { it.id == id }
        if (from < 0 || direction == 0) return
        val to = (from + (if (direction > 0) 1 else -1)).coerceIn(0, tracks.lastIndex)
        if (from == to) return
        val track = tracks.removeAt(from)
        tracks.add(to, track)
        changed()
    }

    @Synchronized fun toggleSolo(id: Int) {
        val track = tracks.firstOrNull { it.id == id } ?: return
        track.solo = !track.solo
        silenceInaudibleTracks()
        changed()
    }

    private fun silenceInaudibleTracks() {
        val anySolo = tracks.any { it.solo }
        tracks.filter { it.muted || (anySolo && !it.solo) }.forEach {
            NativeEngine.midi(NativeEngine.CONTROLLER, it.channel, 123, 0)
            keysDown.remove(it.channel)
        }
    }

    @Synchronized fun removeTrack(id: Int) {
        if (tracks.size <= 1) return
        val track = tracks.firstOrNull { it.id == id } ?: return
        if (recordingTrack == id || armedTrack == id) stopRecord(absoluteTick)
        NativeEngine.midi(NativeEngine.CONTROLLER, track.channel, 123, 0)
        keysDown.remove(track.channel)
        tracks.remove(track)
        silenceInaudibleTracks()
        if (selectedId == id) selectedId = tracks.first().id
        changed()
    }
    @Synchronized fun muteTrack(id: Int) {
        val track = tracks.firstOrNull { it.id == id } ?: return
        track.muted = !track.muted
        silenceInaudibleTracks()
        changed()
    }
    @Synchronized fun setPreset(bank: Int, program: Int) {
        val track = tracks.firstOrNull { it.id == selectedId } ?: return
        track.bank = bank
        track.program = program
        if (track.fontId == "gm" && track.channel != 9) {
            track.category = GmCategories.names[GmCategories.family(program)]
            track.name = GmPrograms.names[program.coerceIn(0, 127)]
        }
        NativeEngine.selectFontPreset(track.channel, fontIndexes[track.fontId] ?: 0, bank, program)
        changed()
    }
    @Synchronized fun setCategory(category: String) {
        val t = tracks.firstOrNull { it.id == selectedId } ?: return
        if (t.channel == 9 || category !in GmCategories.names) return
        t.category = category
        changed()
    }

    @Synchronized fun setInstrument(
        fontId: String, bank: Int, program: Int, presetName: String, category: String
    ) {
        val track = tracks.firstOrNull { it.id == selectedId } ?: return
        if (fontId !in fontIndexes) return
        track.fontId = fontId
        track.bank = if (track.channel == 9) 128 else bank
        track.program = program.coerceIn(0, 127)
        track.name = InstrumentNaming.clean(presetName).ifBlank { category }
        track.category = if (track.channel == 9) "鼓組" else category
        NativeEngine.selectFontPreset(track.channel, fontIndexes[fontId] ?: 0, track.bank, track.program)
        changed()
    }

    @Synchronized fun setFont(fontId: String) {
        val t = tracks.firstOrNull { it.id == selectedId } ?: return
        if (!fontIndexes.containsKey(fontId)) return
        t.fontId = fontId
        NativeEngine.selectFontPreset(t.channel, fontIndexes[fontId] ?: 0, t.bank, t.program)
        changed()
    }
    @Synchronized fun replaceFont(oldId: String, replacementId: String = "gm") {
        tracks.filter { it.fontId == oldId }.forEach { it.fontId = replacementId }
        refreshPrograms()
        changed()
    }

    @Synchronized fun configureFonts(items: List<SoundFontItem>) {
        fontIndexes.clear()
        items.forEachIndexed { index, font -> fontIndexes[font.id] = index }
        refreshPrograms()
        publish()
    }
    @Synchronized fun refreshPrograms() {
        for (t in tracks) {
            NativeEngine.selectFontPreset(
                t.channel, fontIndexes[t.fontId] ?: 0,
                if (t.channel == 9) 128 else t.bank, t.program
            )
        }
    }

    @Synchronized fun renameSelected(name: String) {
        val track = tracks.firstOrNull { it.id == selectedId } ?: return
        if (name.isBlank()) return
        track.name = name.take(24)
        changed()
    }

    @Synchronized fun close() {
        if (recordingTrack != null || armedTrack != null) stopRecord(absoluteTick)
        save()
        scheduler.shutdownNow()
    }
}
