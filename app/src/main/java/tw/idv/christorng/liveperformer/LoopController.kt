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
    val quantizeDivision: Int,
    val metronome: Boolean,
    val playing: Boolean,
    val armed: Boolean,
    val recording: Boolean,
    val selectedId: Int,
    val bar: Int,
    val beat: Int,
    val progress: Float,
    val keyboardNotes: Set<Int>,
    val tracks: List<LoopTrackView>,
    val midiRoutes: List<MidiRoute>
)

/**
 * MIDI-only looper. The clock runs on a dedicated high-priority scheduler thread,
 * not on Compose or the audio callback. Playback dispatch is best-effort (not
 * sample-accurate); all synthesis stays in the existing native Oboe callback.
 */
class LoopController(context: Context) {
    private data class Event(val tick: Int, val type: Int, val note: Int, val velocity: Int)
    private data class Captured(val position: Double, val type: Int, val note: Int, val velocity: Int)
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
    private val midiRoutes = mutableListOf<MidiRoute>()
    private var nextMidiRouteId = 1
    @Volatile private var clockSender: ((Int) -> Unit)? = null
    @Volatile var masterVolumeFromMidi: ((Float) -> Unit)? = null
    private var lastClockIndex = -1L

    fun setClockSender(sender: ((Int) -> Unit)?) { clockSender = sender }

    private val fontIndexes = mutableMapOf("gm" to 0)
    private val fontTitles = mutableMapOf<String, String>()
    private val keysDown = mutableMapOf<Int, MutableSet<Int>>()
    private val scheduler = ScheduledThreadPoolExecutor(1) { command ->
        Thread(command, "LivePerformer-Loop").apply { priority = Thread.MAX_PRIORITY - 1 }
    }
    private var bpm = 120
    private var bars = 2
    private var beatsPerBar = 4
    private var beatUnit = 4
    private var quantizeDivision = 16
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
    private val preRollEvents = mutableListOf<Captured>()
    private var takeHasNotes = false
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
        midiRoutes.clear()
        nextMidiRouteId = 1
        bpm = 120
        bars = 2
        beatsPerBar = 4
        beatUnit = 4
        quantizeDivision = 16
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

    /** Start a new blank project or a project with five empty instrument tracks. */
    @Synchronized fun newProject(withTemplateTracks: Boolean) {
        playing = false
        armedTrack = null
        recordingTrack = null
        preRollEvents.clear()
        heldRecordingNotes.clear()
        takeHasNotes = false
        keysDown.clear()
        NativeEngine.midi(NativeEngine.ALL_SOUNDS_OFF, 0, 0, 0)
        bpm = 120
        bars = 2
        beatsPerBar = 4
        beatUnit = 4
        quantizeDivision = 16
        metronome = true
        absoluteTick = 0.0
        lastScheduled = -0.01
        clockNs = System.nanoTime()
        tracks.clear()
        midiRoutes.clear()
        nextMidiRouteId = 1
        clockSender?.invoke(0xfc)
        if (withTemplateTracks) {
            tracks += Track(1, 9, "鼓組", 128, 0, category = "鼓組")
            tracks += Track(2, 1, "貝斯", 0, 33, category = "貝斯")
            tracks += Track(3, 2, "弦樂", 0, 48, category = "弦樂")
            tracks += Track(4, 3, "Lead", 0, 80, category = "合成器主奏")
            tracks += Track(5, 0, "鋼琴", 0, 0, category = "鋼琴")
        }
        selectedId = if (withTemplateTracks) 5 else 0
        nextId = if (withTemplateTracks) 6 else 1
        refreshPrograms()
        changed()
    }

    @Synchronized fun loadDemo() {
        playing = false
        armedTrack = null
        recordingTrack = null
        heldRecordingNotes.clear()
        preRollEvents.clear()
        takeHasNotes = false
        keysDown.clear()
        NativeEngine.midi(NativeEngine.ALL_SOUNDS_OFF, 0, 0, 0)
        absoluteTick = 0.0
        lastScheduled = -0.01
        clockNs = System.nanoTime()
        clockSender?.invoke(0xfc)
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
        val importedRoutes = mutableListOf<MidiRoute>()
        val routeArray = root.optJSONArray("midiRoutes") ?: JSONArray()
        require(routeArray.length() <= 64) { "MIDI 路由超過上限" }
        val knownActions = setOf("note", "scale", "custom", "volume", "pan", "master", "select-track",
            "mute", "solo", "play-stop", "record-toggle", "metronome-toggle", "next-track")
        for (index in 0 until routeArray.length()) {
            val item = routeArray.getJSONObject(index)
            val kind = item.optString("kind", "note")
            val action = item.optString("action", "note")
            val first = item.optInt("first")
            val last = item.optInt("last", 127)
            val ch = item.optInt("channel", -1)
            val trackId = item.optInt("trackId")
            require(kind in setOf("note", "cc", "program") &&
                action in knownActions && first in 0..127 && last in first..127 &&
                ch in -1..15 && item.optString("deviceKey", "*").length <= 256) {
                "MIDI 路由格式不正確"
            }
            val mapArray = item.optJSONArray("noteMap") ?: JSONArray()
            require(mapArray.length() <= 16 && (action != "custom" ||
                mapArray.length() == last - first + 1)) { "自訂 MIDI 音符映射長度不符" }
            val map = (0 until mapArray.length()).map { mapArray.getInt(it) }
            require(map.all { it in -1..127 }) { "自訂 MIDI 音高無效" }
            val scaleMode = item.optString("scaleMode", "major")
            require(scaleMode in MidiPitch.modes) { "MIDI 調式無效" }
            importedRoutes += MidiRoute(item.optInt("id", index + 1),
                item.optString("deviceKey", "*"), ch, kind, first, last,
                trackId, action, item.optInt("noteBase", -1).coerceIn(-1, 127),
                scaleMode, map)
        }
        val saved = root.getJSONArray("tracks")
        require(saved.length() in 0..16) { "樂器軌數量不正確" }
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
            } else if (!importing) {
                tracks.firstOrNull { it.id == id && it.channel == channel }?.let { old ->
                    track.events.addAll(old.events)
                }
            }
            track.events.sortBy { it.tick }
            newTracks += track
        }
        if (importing) {
            clockSender?.invoke(0xfc)
            lastClockIndex = -1L
            playing = false
            clockSender?.invoke(0xfc)
            NativeEngine.midi(NativeEngine.ALL_SOUNDS_OFF, 0, 0, 0)
            armedTrack = null
            recordingTrack = null
            preRollEvents.clear()
            heldRecordingNotes.clear()
            takeHasNotes = false
            keysDown.clear()
            absoluteTick = 0.0
            lastScheduled = -0.01
            clockNs = System.nanoTime()
        }
        bars = newBars
        beatsPerBar = newNumerator
        beatUnit = newDenominator
        quantizeDivision = root.optInt("quantizeDivision", 16)
            .takeIf { it in LoopMath.supportedQuantization } ?: 16
        bpm = root.optInt("bpm", 120).coerceIn(40, 240)
        metronome = root.optBoolean("metronome", true)
        selectedId = root.optInt("selectedId", newTracks.firstOrNull()?.id ?: 0)
        nextId = maxOf(root.optInt("nextId", 1), (newTracks.maxOfOrNull { it.id } ?: 0) + 1)
        tracks.clear()
        tracks.addAll(newTracks)
        midiRoutes.clear()
        midiRoutes.addAll(importedRoutes.filter { it.trackId == 0 ||
            tracks.any { track -> track.id == it.trackId } })
        nextMidiRouteId = (midiRoutes.maxOfOrNull { it.id } ?: 0) + 1
        if (tracks.none { it.id == selectedId }) selectedId = tracks.firstOrNull()?.id ?: 0
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
            .put("quantizeDivision", quantizeDivision)
            .put("metronome", metronome)
            .put("selectedId", selectedId).put("nextId", nextId)
        if (fileFormat) {
            root.put("format", "liveperformer.v1")
            root.put("type", if (settingsOnly) "settings" else "project")
        }
        val routes = JSONArray()
        midiRoutes.forEach { r ->
            routes.put(JSONObject().put("id", r.id).put("deviceKey", r.deviceKey)
                .put("channel", r.channel).put("kind", r.kind)
                .put("first", r.first).put("last", r.last)
                .put("trackId", r.trackId).put("action", r.action)
                .put("noteBase", r.noteBase).put("scaleMode", r.scaleMode)
                .put("noteMap", JSONArray(r.noteMap)))
        }
        root.put("midiRoutes", routes)
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
        return LoopView(bpm, bars, beatsPerBar, beatUnit, quantizeDivision, metronome, playing, armedTrack != null,
            recordingTrack != null, selectedId, at / LoopMath.barTicks(beatsPerBar, beatUnit) + 1,
            (at / LoopMath.beatTicks(beatUnit)) % beatsPerBar + 1, at.toFloat() / length,
            keysDown[tracks.firstOrNull { it.id == selectedId }?.channel]?.toSet() ?: emptySet(),
            tracks.map {
                LoopTrackView(it.id, it.name, it.channel, it.bank, it.program, it.muted, it.solo,
                    it.events.count { event -> event.type == NativeEngine.NOTE_ON },
                    it.fontId, it.category.ifBlank {
                        if (it.channel == 9) "鼓組" else GmCategories.names[GmCategories.family(it.program)]
                    }, pianoNotes(it, length))
            }, midiRoutes.toList())
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

    /** Commit a new take only when a real NoteOn occurred. Silence preserves the old track. */
    private fun stopRecord(at: Double) {
        val track = tracks.firstOrNull { it.id == recordingTrack }
        if (track != null && takeHasNotes) {
            val length = LoopMath.loopTicks(bars, beatsPerBar, beatUnit)
            val end = LoopMath.quantize(at - recordStart, quantizeDivision, length)
            heldRecordingNotes.forEach { note ->
                val prior = track.events.lastOrNull {
                    it.type == NativeEngine.NOTE_ON && it.note == note
                }?.tick ?: 0
                track.events += Event(end.coerceAtLeast(prior + 1).coerceAtMost(length - 1),
                    NativeEngine.NOTE_OFF, note, 0)
            }
            track.events.sortWith(compareBy<Event> { it.tick }.thenByDescending { it.type })
            dirty = true
            hasUnsavedChanges = true
        }
        heldRecordingNotes.clear()
        preRollEvents.clear()
        takeHasNotes = false
        recordingTrack = null
        armedTrack = null
    }

    private fun capture(track: Track, type: Int, note: Int, velocity: Int, position: Double) {
        val length = LoopMath.loopTicks(bars, beatsPerBar, beatUnit)
        if (type == NativeEngine.NOTE_ON && velocity > 0 && !takeHasNotes) {
            // Replace, never overdub. Keep the previous recording through an empty pass.
            track.events.clear()
            heldRecordingNotes.clear()
            // Do not cut off a live note that began just before the downbeat.
            takeHasNotes = true
        }
        if (!takeHasNotes) return
        val normalizedType = if (type == NativeEngine.NOTE_ON && velocity == 0)
            NativeEngine.NOTE_OFF else type
        if (normalizedType == NativeEngine.NOTE_OFF && note !in heldRecordingNotes) return
        val tick = LoopMath.quantize(position - recordStart, quantizeDivision, length,
            wrap = normalizedType == NativeEngine.NOTE_ON)
        val safeTick = if (normalizedType == NativeEngine.NOTE_OFF) {
            val prior = track.events.lastOrNull {
                it.type == NativeEngine.NOTE_ON && it.note == note
            }?.tick ?: 0
            tick.coerceAtLeast(prior + 1).coerceAtMost(length - 1)
        } else tick
        track.events += Event(safeTick, normalizedType, note, velocity.coerceIn(0, 127))
        if (normalizedType == NativeEngine.NOTE_ON) heldRecordingNotes.add(note)
        else heldRecordingNotes.remove(note)
        dirty = true
        hasUnsavedChanges = true
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
            takeHasNotes = false
            val track = tracks.firstOrNull { it.id == recordingTrack }
            if (track != null) {
                // Pick up a downbeat played slightly early, within half a quantization step.
                preRollEvents.forEach { e -> capture(track, e.type, e.note, e.velocity, e.position) }
            }
            preRollEvents.clear()
        }
        if (recordingTrack != null && absoluteTick >= recordStart + LoopMath.loopTicks(bars, beatsPerBar, beatUnit))
            stopRecord(recordStart + LoopMath.loopTicks(bars, beatsPerBar, beatUnit) - 1)
    }

    @Synchronized private fun tick() {
        val now = System.nanoTime()
        advance(now)
        updateRecording()
        if (playing) {
            // MIDI Clock: 24 pulses per quarter note. This is scheduler-timed (~4 ms),
            // not hardware timestamped, and is not suitable for sample-accurate sync.
            val currentClock = floor(absoluteTick / (LoopMath.PPQ / 24)).toLong()
            if (clockSender != null) {
                if (currentClock - lastClockIndex > 4) lastClockIndex = currentClock - 4
                while (lastClockIndex < currentClock) {
                    lastClockIndex++
                    clockSender?.invoke(0xf8)
                }
            } else lastClockIndex = currentClock
            val length = LoopMath.loopTicks(bars, beatsPerBar, beatUnit)
            val anySolo = tracks.any { it.solo }
            for (track in tracks) if (!track.muted && (!anySolo || track.solo) && !(recordingTrack == track.id && takeHasNotes)) for (e in track.events) {
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

    /** Transport has Play/Stop semantics, never resume-from-middle. */
    @Synchronized fun playStop() {
        val now = System.nanoTime()
        advance(now)
        if (playing) {
            if (recordingTrack != null || armedTrack != null) stopRecord(absoluteTick)
            playing = false
            NativeEngine.midi(NativeEngine.ALL_SOUNDS_OFF, 0, 0, 0)
            keysDown.clear()
        } else {
            playing = true
            clockSender?.invoke(0xfa)
        }
        lastClockIndex = -1L
        absoluteTick = 0.0
        lastScheduled = -0.01
        clockNs = now
        publish()
        save()
    }

    @Synchronized fun playPause() = playStop()

    @Synchronized fun recordPause() {
        advance(System.nanoTime())
        if (recordingTrack != null || armedTrack != null) {
            stopRecord(absoluteTick)
            changed()
            return
        }
        preRollEvents.clear()
        takeHasNotes = false
        if (!playing) {
            absoluteTick = 0.0
            lastScheduled = -0.01
            playing = true
            lastClockIndex = -1L
            clockSender?.invoke(0xfa)
            clockNs = System.nanoTime()
            armedAt = 0.0
        } else armedAt = LoopMath.nextBoundary(absoluteTick,
            LoopMath.loopTicks(bars, beatsPerBar, beatUnit))
        armedTrack = selectedId.takeIf { id -> tracks.any { it.id == id } }
        updateRecording()
        publish()
    }

    @Synchronized fun clearSelected() = clearTrack(selectedId)

    @Synchronized fun clearTrack(id: Int) {
        val t = tracks.firstOrNull { it.id == id } ?: return
        if (recordingTrack == t.id || armedTrack == t.id) stopRecord(absoluteTick)
        t.events.clear()
        NativeEngine.midi(NativeEngine.CONTROLLER, t.channel, 123, 0)
        changed()
    }

    /** Touch keyboard always plays the selected track. Physical MIDI takes independent routes. */
    @Synchronized fun onMidi(type: Int, originalChannel: Int, a: Int, b: Int) {
        val selected = tracks.firstOrNull { it.id == selectedId } ?: return
        dispatchToTrack(selected, type, a, b)
    }

    private fun dispatchToTrack(track: Track, type: Int, a: Int, b: Int) {
        advance(System.nanoTime())
        updateRecording()
        if (type == NativeEngine.NOTE_ON || type == NativeEngine.NOTE_OFF) {
            val note = a.coerceIn(0, 127)
            val velocity = b.coerceIn(0, 127)
            if (recordingTrack == track.id) {
                capture(track, type, note, velocity, absoluteTick)
            } else if (armedTrack == track.id &&
                absoluteTick >= armedAt - LoopMath.preRollTicks(quantizeDivision) &&
                absoluteTick < armedAt) {
                preRollEvents += Captured(absoluteTick, type, note, velocity)
            }
        }
        displayKey(track.channel, type, a)
        if (!track.muted && (tracks.none { it.solo } || track.solo))
            NativeEngine.midi(type, track.channel, a, b)
        if (type == NativeEngine.NOTE_ON || type == NativeEngine.NOTE_OFF) publish()
    }

    /** Physical source channel is independent from the synth's assigned track channel. */
    @Synchronized fun onExternalMidi(event: MidiEvent) {
        val routes = midiRoutes.filter { it.matches(event) }
        if (routes.isNotEmpty()) {
            for (route in routes) {
                val track = tracks.firstOrNull { it.id == route.trackId }
                when (route.action) {
                    "note", "scale", "custom" -> if (track != null) {
                        val result = route.noteFor(event) ?: continue
                        dispatchToTrack(track, if (result.second > 0)
                            NativeEngine.NOTE_ON else NativeEngine.NOTE_OFF,
                            result.first, result.second)
                    }
                    "volume", "pan" -> if (track != null) {
                        NativeEngine.midi(NativeEngine.CONTROLLER, track.channel,
                            if (route.action == "volume") 7 else 10, event.data2)
                    }
                    "master" -> masterVolumeFromMidi?.invoke(
                        0.1f + event.data2.coerceIn(0, 127) / 127f * 1.4f)
                    // Toggle CC sends alternating >=64 / <64. Set the requested state
                    // rather than blindly toggling on every received message.
                    "mute" -> if (track != null && event.kind == 0xb0)
                        setTrackMuted(track.id, event.data2 >= 64)
                    "solo" -> if (track != null && event.kind == 0xb0)
                        setTrackSolo(track.id, event.data2 >= 64)
                    // Momentary CC: act on press only, never on its release.
                    "play-stop" -> if (event.kind == 0xb0 && event.data2 >= 64) playStop()
                    "record-toggle" -> if (event.kind == 0xb0 && event.data2 >= 64) recordPause()
                    "metronome-toggle" -> if (event.kind == 0xb0 && event.data2 >= 64)
                        setMetronome(!metronome)
                    "next-track" -> if (event.kind == 0xb0 && event.data2 >= 64 && tracks.isNotEmpty()) {
                        val current = tracks.indexOfFirst { it.id == selectedId }
                        selectTrack(tracks[(current + 1) % tracks.size].id)
                    }
                    "select-track" -> if (track != null && event.kind == 0xc0)
                        selectTrack(track.id)
                }
            }
            return
        }
        // Preserve the original keyboard behaviour until a route is configured.
        // A partially mapped pad bank must not leak into the currently selected instrument.
        val isMappedGroup = midiRoutes.any {
            (it.deviceKey == "*" || it.deviceKey == event.deviceKey) &&
                (it.channel == -1 || it.channel == event.channel) &&
                ((it.kind == "note" && event.kind in setOf(0x80, 0x90)) ||
                 (it.kind == "cc" && event.kind == 0xb0))
        }
        if (isMappedGroup && event.channel != 0) return
        val selected = tracks.firstOrNull { it.id == selectedId } ?: return
        when (event.kind) {
            0x80 -> dispatchToTrack(selected, NativeEngine.NOTE_OFF, event.data1, event.data2)
            0x90 -> dispatchToTrack(selected,
                if (event.data2 == 0) NativeEngine.NOTE_OFF else NativeEngine.NOTE_ON,
                event.data1, event.data2)
            0xb0 -> if (event.channel == 0 || midiRoutes.isEmpty())
                dispatchToTrack(selected, NativeEngine.CONTROLLER, event.data1, event.data2)
            0xe0 -> dispatchToTrack(selected, NativeEngine.PITCH_BEND, 0,
                event.data1 or (event.data2 shl 7))
            // Program Change is diagnostic-only by default: it must not silently
            // overwrite the SoundFont preset chosen by the project.
        }
    }

    /** Atomic create/update: saving an edited pad bank never duplicates its old rule. */
    @Synchronized fun putMidiRoute(
        id: Int?, deviceKey: String, channel: Int, kind: String, first: Int, last: Int,
        trackId: Int, action: String, noteBase: Int = -1,
        scaleMode: String = "major", noteMap: List<Int> = emptyList()
    ): Boolean {
        if ((id == null && midiRoutes.size >= 48) || (id != null && midiRoutes.none { it.id == id }) ||
            deviceKey.length > 256 || channel !in -1..15 ||
            kind !in setOf("note", "cc", "program") ||
            action !in setOf("note", "scale", "custom", "volume", "pan", "master", "select-track",
                "mute", "solo", "play-stop", "record-toggle", "metronome-toggle", "next-track") ||
            (kind == "program" && action != "select-track") ||
            (kind != "program" && action == "select-track") ||
            (action in setOf("volume", "pan", "master", "mute", "solo",
                "play-stop", "record-toggle", "metronome-toggle", "next-track") && kind != "cc") ||
            first !in 0..127 || last !in first..127 ||
            (trackId != 0 && tracks.none { it.id == trackId }) ||
            (trackId == 0) != (action in setOf("master", "play-stop", "record-toggle",
                "metronome-toggle", "next-track")) ||
            noteBase !in -1..127 || scaleMode !in MidiPitch.modes ||
            (action == "scale" && noteBase < 0) ||
            (action == "custom" && (last - first + 1 > 16 ||
                noteMap.size != last - first + 1 || noteMap.any { it !in -1..127 })))
            return false
        val candidate = MidiRoute(id ?: nextMidiRouteId++, deviceKey, channel, kind,
            first, last, trackId, action, noteBase, scaleMode,
            if (action == "custom") noteMap.toList() else emptyList())
        val oldIndex = midiRoutes.indexOfFirst { it.id == id }
        if (oldIndex < 0) midiRoutes += candidate else midiRoutes[oldIndex] = candidate
        changed()
        return true
    }

    @Synchronized fun addMidiRoute(
        deviceKey: String, channel: Int, kind: String, first: Int, last: Int,
        trackId: Int, action: String, noteBase: Int = -1
    ): Boolean = putMidiRoute(null, deviceKey, channel, kind, first, last, trackId, action, noteBase)


    @Synchronized fun removeMidiRoute(id: Int) {
        if (midiRoutes.removeAll { it.id == id }) changed()
    }

    @Synchronized fun clearMidiRoutes() {
        if (midiRoutes.isNotEmpty()) { midiRoutes.clear(); changed() }
    }

    /** Matches the pad-note numbers shown in the user's MPK mini MK2 Editor screenshot. */
    @Synchronized fun applyMpkMiniMk2Preset(deviceKey: String): Boolean {
        val drums = tracks.firstOrNull { it.category == "鼓組" || it.bank == 128 } ?: return false
        val bass = tracks.firstOrNull {
            it.id != drums.id && (it.name.contains("Bass", true) ||
                it.name.contains("貝斯") || it.category == "貝斯")
        } ?: return false
        val desired = listOf(Triple(36, 43, drums.id), Triple(44, 51, bass.id))
        val missing = desired.filterNot { (first, last, trackId) ->
            midiRoutes.any { it.deviceKey == deviceKey && it.channel == 1 &&
                it.kind == "note" && it.first == first && it.last == last &&
                it.trackId == trackId && it.action == "note" && it.noteBase == -1 }
        }
        if (midiRoutes.size + missing.size > 48) return false
        missing.forEach { (first, last, trackId) ->
            addMidiRoute(deviceKey, 1, "note", first, last, trackId, "note")
        }
        return true
    }

    /** One-tap example: MPK mini MK2 CC Pad A (20..27) plays C2 major on Bass. */
    @Synchronized fun applyMpkMiniMk2CcBassPreset(deviceKey: String): Boolean {
        val bass = tracks.firstOrNull {
            it.name.contains("Bass", true) || it.name.contains("貝斯") ||
                it.category == "貝斯"
        } ?: return false
        if (midiRoutes.any {
            (it.deviceKey == deviceKey || it.deviceKey == "*") &&
                it.channel == 1 && it.kind == "cc" &&
                it.first <= 27 && it.last >= 20
        }) return false // Do not silently override an already-customized CC bank.
        return putMidiRoute(null, deviceKey, 1, "cc", 20, 27,
            bass.id, "scale", 36, "major")
    }

    /** Optional preset for the dedicated CC Bank B. The hardware must have
     * pads 1..4 set to Toggle and pads 5..8 set to Momentary. */
    @Synchronized fun applyMpkMiniMk2StagePreset(deviceKey: String): Boolean {
        val drums = tracks.firstOrNull { it.category == "鼓組" || it.bank == 128 } ?: return false
        val bass = tracks.firstOrNull { it.category == "貝斯" ||
            it.name.contains("Bass", ignoreCase = true) ||
            it.name.contains("貝斯") } ?: return false
        val strings = tracks.firstOrNull { it.category == "弦樂" ||
            it.name.contains("弦樂") } ?: return false
        val lead = tracks.firstOrNull { it.id !in setOf(drums.id, bass.id, strings.id) &&
            (it.name.contains("Lead", ignoreCase = true) ||
             it.category.contains("主奏")) } ?: return false
        if (midiRoutes.size + 8 > 48 ||
            midiRoutes.any { (it.deviceKey == deviceKey || it.deviceKey == "*") &&
                (it.channel == 1 || it.channel == -1) && it.kind == "cc" &&
                it.first <= 35 && it.last >= 28 }) return false
        val targets = listOf(drums.id, bass.id, strings.id, lead.id)
        for (i in 0..3) {
            if (!putMidiRoute(null, deviceKey, 1, "cc", 28 + i, 28 + i,
                targets[i], "mute")) return false
        }
        val commands = listOf("play-stop", "record-toggle", "metronome-toggle", "next-track")
        for (i in 0..3) {
            if (!putMidiRoute(null, deviceKey, 1, "cc", 32 + i, 32 + i,
                0, commands[i])) return false
        }
        return true
    }

    @Synchronized fun setBpm(value: Int) {
        advance(System.nanoTime())
        bpm = value.coerceIn(40, 240)
        changed()
    }
    @Synchronized fun setQuantization(division: Int) {
        require(division in LoopMath.supportedQuantization)
        quantizeDivision = division
        changed()
    }

    /** Whether changing the time signature/length discards any saved events. */
    @Synchronized fun wouldTruncate(newBars: Int, numerator: Int, denominator: Int): Boolean {
        val limit = LoopMath.loopTicks(newBars, numerator, denominator)
        return tracks.any { t -> t.events.any { it.tick >= limit } }
    }

    private fun trimBeyondLoop() {
        val limit = LoopMath.loopTicks(bars, beatsPerBar, beatUnit)
        for (t in tracks) {
            if (t.events.removeAll { it.tick >= limit }) {
                val active = mutableSetOf<Int>()
                t.events.sortedBy { it.tick }.forEach { e ->
                    if (e.type == NativeEngine.NOTE_ON) active += e.note
                    else if (e.type == NativeEngine.NOTE_OFF) active -= e.note
                }
                active.forEach { note ->
                    t.events += Event(limit - 1, NativeEngine.NOTE_OFF, note, 0)
                }
                t.events.sortBy { it.tick }
            }
        }
    }

    /** Apply meter and bar count together so only the final length determines truncation. */
    @Synchronized fun setStructure(newBars: Int, numerator: Int, denominator: Int) {
        require((numerator to denominator) in setOf(2 to 4, 3 to 4, 4 to 4, 6 to 8))
        if (newBars == bars && beatsPerBar == numerator && beatUnit == denominator) return
        if (recordingTrack != null || armedTrack != null) stopRecord(absoluteTick)
        bars = newBars.coerceIn(1, 8)
        beatsPerBar = numerator
        beatUnit = denominator
        trimBeyondLoop()
        absoluteTick = 0.0
        lastScheduled = -0.01
        clockNs = System.nanoTime()
        NativeEngine.midi(NativeEngine.ALL_SOUNDS_OFF, 0, 0, 0)
        changed()
    }
    @Synchronized fun setBars(value: Int) = setStructure(value, beatsPerBar, beatUnit)
    @Synchronized fun setMeter(numerator: Int, denominator: Int) =
        setStructure(bars, numerator, denominator)
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
        val channel = (if (drums) listOf(9) + (0..15).filter { it != 9 }
            else (0..15).filter { it != 9 })
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
        val channel = (if (drums) listOf(9) + (0..15).filter { it != 9 }
            else (0..15).filter { it != 9 })
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
        if (track.solo) track.muted = false
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
        val track = tracks.firstOrNull { it.id == id } ?: return
        if (recordingTrack == id || armedTrack == id) stopRecord(absoluteTick)
        NativeEngine.midi(NativeEngine.CONTROLLER, track.channel, 123, 0)
        keysDown.remove(track.channel)
        tracks.remove(track)
        midiRoutes.removeAll { it.trackId == id }
        silenceInaudibleTracks()
        if (selectedId == id) selectedId = tracks.firstOrNull()?.id ?: 0
        changed()
    }
    @Synchronized fun setTrackMuted(id: Int, value: Boolean) {
        val track = tracks.firstOrNull { it.id == id } ?: return
        if (track.muted == value) return
        track.muted = value
        if (value) track.solo = false
        silenceInaudibleTracks()
        changed()
    }

    @Synchronized fun setTrackSolo(id: Int, value: Boolean) {
        val track = tracks.firstOrNull { it.id == id } ?: return
        if (track.solo == value) return
        track.solo = value
        if (value) track.muted = false
        silenceInaudibleTracks()
        changed()
    }

    @Synchronized fun muteTrack(id: Int) {
        val track = tracks.firstOrNull { it.id == id } ?: return
        track.muted = !track.muted
        if (track.muted) track.solo = false
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
        track.bank = bank
        track.program = program.coerceIn(0, 127)
        track.name = InstrumentNaming.clean(presetName).ifBlank { category }
        track.category = category
        NativeEngine.selectFontPreset(track.channel, fontIndexes[fontId] ?: 0, track.bank, track.program)
        changed()
    }

    private fun recoverInstrumentName(track: Track): Boolean {
        if (track.fontId == "gm") return false
        val source = fontTitles[track.fontId] ?: return false
        val old = track.name.trim()
        val generic = old.matches(Regex("(instrument|preset|樂器|鋼琴)( *[0-9]+)?",
            RegexOption.IGNORE_CASE)) || old == "未命名樂器"
        if (!generic) return false
        track.name = source
        track.category = InstrumentNaming.category(source, track.program, "Instrument")
        return true
    }

    @Synchronized fun setFont(fontId: String) {
        val t = tracks.firstOrNull { it.id == selectedId } ?: return
        if (!fontIndexes.containsKey(fontId)) return
        t.fontId = fontId
        recoverInstrumentName(t)
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
        fontTitles.clear()
        items.forEachIndexed { index, font ->
            fontIndexes[font.id] = index
            fontTitles[font.id] = font.title
        }
        val repaired = tracks.map { recoverInstrumentName(it) }.any { it }
        refreshPrograms()
        if (repaired) changed() else publish()
    }
    @Synchronized fun refreshPrograms() {
        for (t in tracks) {
            NativeEngine.selectFontPreset(
                t.channel, fontIndexes[t.fontId] ?: 0,
                t.bank, t.program
            )
        }
    }

    /** Only tracks still bearing an auto-generated title are updated when their
     * one-preset SoundFont is renamed. Explicit user track names stay unchanged. */
    @Synchronized fun renameSinglePresetTracks(
        fontId: String, oldNames: List<String>, newName: String
    ) {
        val old = oldNames.map(InstrumentNaming::clean).filter { it.isNotBlank() }.toSet()
        val target = InstrumentNaming.clean(newName)
        if (target.isBlank()) return
        var updated = false
        for (track in tracks) {
            if (track.fontId == fontId && track.name in old) {
                track.name = target
                updated = true
            }
        }
        if (updated) changed()
    }

    @Synchronized fun renameSelected(name: String) = renameTrack(selectedId, name)

    @Synchronized fun renameTrack(id: Int, name: String) {
        val track = tracks.firstOrNull { it.id == id } ?: return
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
