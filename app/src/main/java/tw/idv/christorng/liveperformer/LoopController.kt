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
    val volume: Int,
    val notes: Int,
    val fontId: String,
    val category: String,
    val noteRects: List<LoopNoteView>,
    val activityLevel: Float,
    val keyboardBase: Int,
    val pitchRange: IntRange?,
    val isAudio: Boolean = false,
    val waveform: List<Float> = emptyList(),
    val audioFraction: Float = 1f
)
data class LoopView(
    val bpm: Int,
    val bars: Int,
    val beatsPerBar: Int,
    val beatUnit: Int,
    val quantizeDivision: Int,
    val metronome: Boolean,
    val metronomeVolume: Int,
    val playing: Boolean,
    val armed: Boolean,
    val recording: Boolean,
    val selectedId: Int,
    val inputTargetIds: Set<Int>,
    val bar: Int,
    val beat: Int,
    val progress: Float,
    val keyboardNotes: Set<Int>,
    val keyboardVelocities: Map<Int, Int>,
    val tracks: List<LoopTrackView>,
    val midiRoutes: List<MidiRoute>,
    val countInEnabled: Boolean,
    val countInBars: Int,
    val countInBeatsLeft: Int,
    val tapCount: Int,
    val audioError: String? = null
)

/**
 * MIDI-only looper. The clock runs on a dedicated high-priority scheduler thread,
 * not on Compose or the audio callback. Playback dispatch is best-effort (not
 * sample-accurate); all synthesis stays in the existing native Oboe callback.
 */
class LoopController(context: Context) {
    private data class Event(val tick: Int, val type: Int, val note: Int, val velocity: Int)
    private data class Captured(val trackId: Int, val position: Double, val type: Int, val note: Int, val velocity: Int)
    private data class Track(
        val id: Int, val channel: Int, var name: String,
        var bank: Int, var program: Int, var muted: Boolean = false,
        var fontId: String = "gm",
        var category: String = "",
        var solo: Boolean = false,
        var volume: Int = 100,
        var keyboardBase: Int = -1,
        val events: MutableList<Event> = mutableListOf()
    ) {
        val isAudio get() = channel < 0
        var pcm = ShortArray(0)
        var waveform = emptyList<Float>()
        fun replacePcm(value: ShortArray) { pcm = value; waveform = LoopPcm.peaks(value) }
        val heldVelocities = mutableMapOf<Int, Int>()
        var activityLevel = 0f // MIDI activity proxy, not measured PCM amplitude.
    }

    private val prefs = context.applicationContext.getSharedPreferences("loop-session", Context.MODE_PRIVATE)
    private val drumMaps = DrumMapStore(context)
    private val mpkPrefs = context.applicationContext.getSharedPreferences(
        "mpk-control-defaults", Context.MODE_PRIVATE
    )

    /** v0.4.10 used CC1–8. Migrate that unverified preset to MKII's real CC2–9
     * without losing deliberate custom CC70–77; new selections use a v2 key. */
    @Synchronized fun defaultKnobFirstCc(deviceKey: String): Int {
        val modern = mpkPrefs.getInt("knobFirstV2:$deviceKey", -1)
        if (modern in 0..120) return modern
        val legacy = mpkPrefs.getInt("knobFirst:$deviceKey",
            mpkPrefs.getInt("knobFirst:*", 2))
        return legacy.takeUnless { it == 1 }?.takeIf { it in 0..120 } ?: 2
    }
    private val appContext = context.applicationContext
    private val sessionFile = android.util.AtomicFile(java.io.File(appContext.filesDir, "loop-session.json"))
    private var audioCapture: AudioLoopCapture? = null
    private var audioTargets: Set<Int> = emptySet()
    private val uploadedAudio = arrayOfNulls<ShortArray>(8)
    @Volatile var audioError: String? = null
        private set
    private fun loopNs(): Long = (LoopMath.loopTicks(bars, beatsPerBar, beatUnit) *
        60_000_000_000.0 / (bpm * LoopMath.PPQ)).toLong()
    private fun loopSamples(): Int = (loopNs() / 1e9 * LoopPcm.RATE).toInt()
    private fun cancelAudio() {
        audioCapture?.cancel()
        audioCapture = null
        audioTargets = emptySet()
    }
    private fun finishAudio() {
        val input = audioCapture
        if (input == null) { audioTargets = emptySet(); return }
        val result = input.finish()
        audioError = input.error ?: if (result == null) "未取得錄音，保留原音訊" else null
        if (result != null) {
            tracks.filter { it.id in audioTargets }.forEach { it.replacePcm(result) }
            dirty = true
            hasUnsavedChanges = true
        }
        audioCapture = null
        audioTargets = emptySet()
    }
    private fun syncAudio() {
        val solo = tracks.any { it.solo }
        for (slot in 0 until 8) {
            val track = tracks.firstOrNull { it.channel == -slot - 1 }
            val pcm = track?.pcm
            if (uploadedAudio[slot] !== pcm) {
                NativeEngine.setAudioClip(slot, pcm ?: ShortArray(0))
                uploadedAudio[slot] = pcm
            }
            NativeEngine.setAudioVolume(slot, if (track == null || track.muted ||
                (solo && !track.solo) || track.id in audioTargets) 0f else track.volume / 127f)
        }
        if (playing && !countInActive) {
            val position = absoluteTick % LoopMath.loopTicks(bars, beatsPerBar, beatUnit)
            val elapsed = (position * 60_000_000_000.0 / (bpm * LoopMath.PPQ)).toLong()
            NativeEngine.setAudioClock(clockNs - elapsed, loopNs())
        } else NativeEngine.setAudioClock(0, 0)
    }
    @Synchronized fun audioPermissionNeeded(): Boolean =
        recordingTrack == null && armedTrack == null &&
            tracks.any { it.isAudio && it.id in inputTargetIds } &&
            appContext.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
    private val tracks = mutableListOf<Track>()
    private val midiRoutes = mutableListOf<MidiRoute>()
    private val inputTargetIds = linkedSetOf<Int>()
    private val takeHasNotesByTrack = mutableSetOf<Int>()
    /** Continuous CC11 curves are staged separately, so expression-only takes
     * never erase existing notes, and a later first NoteOn cannot erase the curve. */
    private val pendingExpressionByTrack = mutableMapOf<Int, MutableList<Event>>()
    private val heldRecordingNotesByTrack = mutableMapOf<Int, MutableSet<Int>>()
    private var armedTargets: Set<Int> = emptySet()
    private var recordingTargets: Set<Int> = emptySet()
    private val tapTracker = TapTempoTracker()
    private var lastTapMillis = 0L
    private var skipInitialTapClick = false
    private var nextMidiRouteId = 1
    @Volatile private var clockSender: ((Int) -> Unit)? = null
    @Volatile var masterVolumeFromMidi: ((Float) -> Unit)? = null
    private val liveInputOwners = mutableMapOf<String, MutableSet<Int>>()
    private var lastClockIndex = -1L

    fun setClockSender(sender: ((Int) -> Unit)?) { clockSender = sender }

    private val fontIndexes = mutableMapOf("gm" to 0)
    private val fontTitles = mutableMapOf<String, String>()
    private val keysDown = mutableMapOf<Int, MutableSet<Int>>()
    private val keyVelocities = mutableMapOf<Int, MutableMap<Int, Int>>()
    private val physicalKeybed = MidiKeybedMonitor()
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
    private var metronomeVolume = 100
    private var countInEnabled = true
    private var countInBars = 1
    private var countInActive = false
    private var countInProgress = 0.0
    private var lastCountInScheduled = -0.01
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
        metronomeVolume = 100
        countInEnabled = true
        countInBars = 1
        countInActive = false
        nextId = 7
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
        tracks.addAll(listOf(drums,bass,strings,lead,piano, Track(6, -1, "音訊 1", 0, 0, category = "音訊")))
        listOf("鼓組","貝斯","弦樂","Lead","鋼琴").forEachIndexed { i, name ->
            tracks[i].category = name
        }
        tracks.forEach { it.events.sortBy { event -> event.tick } }
        inputTargetIds.clear()
        inputTargetIds += selectedId
        dirty = true
    }

    private fun hit(track: Track, tick: Int, note: Int, velocity: Int, duration: Int = LoopMath.PPQ / 8) {
        track.events += Event(tick, NativeEngine.NOTE_ON, note, velocity)
        track.events += Event((tick + duration).coerceAtMost(LoopMath.loopTicks(2) - 1),
            NativeEngine.NOTE_OFF, note, 0)
    }

    /** Start a new blank project or a project with five empty instrument tracks. */
    @Synchronized fun newProject(withTemplateTracks: Boolean) {
        cancelAudio()
        playing = false
        armedTrack = null
        recordingTrack = null
        preRollEvents.clear()
        heldRecordingNotes.clear()
        takeHasNotes = false
        takeHasNotesByTrack.clear()
        pendingExpressionByTrack.clear()
        heldRecordingNotesByTrack.clear()
        recordingTargets = emptySet()
        armedTargets = emptySet()
        keysDown.clear()
        keyVelocities.clear()
        NativeEngine.midi(NativeEngine.ALL_SOUNDS_OFF, 0, 0, 0)
        liveInputOwners.clear()
        physicalKeybed.clear()
        bpm = 120
        bars = 2
        beatsPerBar = 4
        beatUnit = 4
        quantizeDivision = 16
        metronome = true
        metronomeVolume = 100
        countInEnabled = true
        countInBars = 1
        countInActive = false
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
            tracks += Track(6, -1, "音訊 1", 0, 0, category = "音訊")
        }
        selectedId = if (withTemplateTracks) 5 else 0
        inputTargetIds.clear()
        if (selectedId != 0) inputTargetIds += selectedId
        nextId = if (withTemplateTracks) 7 else 1
        refreshPrograms()
        changed()
    }

    @Synchronized fun loadDemo() {
        cancelAudio()
        playing = false
        armedTrack = null
        recordingTrack = null
        heldRecordingNotes.clear()
        preRollEvents.clear()
        takeHasNotes = false
        takeHasNotesByTrack.clear()
        pendingExpressionByTrack.clear()
        heldRecordingNotesByTrack.clear()
        armedTargets = emptySet()
        recordingTargets = emptySet()
        keysDown.clear()
        keyVelocities.clear()
        countInActive = false
        NativeEngine.midi(NativeEngine.ALL_SOUNDS_OFF, 0, 0, 0)
        liveInputOwners.clear()
        physicalKeybed.clear()
        absoluteTick = 0.0
        lastScheduled = -0.01
        clockNs = System.nanoTime()
        clockSender?.invoke(0xfc)
        installDemo()
        refreshPrograms()
        changed()
    }

    @Synchronized private fun restore(): Boolean = runCatching {
        val stored = if (sessionFile.baseFile.exists()) sessionFile.readFully().toString(Charsets.UTF_8)
            else prefs.getString("data", null) ?: return@runCatching false
        decode(stored, false)
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
        val importedRoutes = decodeMidiRoutes(root.optJSONArray("midiRoutes") ?: JSONArray())
        val saved = root.getJSONArray("tracks")
        require(saved.length() in 0..24) { "樂器軌數量不正確" }
        for (i in 0 until saved.length()) {
            val obj = saved.getJSONObject(i)
            val channel = obj.getInt("channel")
            require(channel in -8..15 && occupied.add(channel)) { "MIDI 聲道重複或無效" }
            val id = obj.getInt("id")
            val track = Track(id, channel, obj.optString("name", "樂器"),
                obj.optInt("bank"), obj.optInt("program").coerceIn(0, 127),
                obj.optBoolean("muted"), obj.optString("fontId", "gm").ifBlank { "gm" },
                obj.optString("category", "").ifBlank {
                    if (channel == 9) "鼓組" else GmCategories.names[GmCategories.family(obj.optInt("program"))]
                }, obj.optBoolean("solo", false), obj.optInt("volume", 100).coerceIn(0, 127),
                obj.optInt("keyboardBase", -1).coerceIn(-1, 127))
            if (track.isAudio) {
                track.category = "音訊"
                if (!settingsOnly) track.replacePcm(LoopPcm.decode(obj.optString("pcm", "")))
            } else if (!settingsOnly) {
                val notes = obj.optJSONArray("events") ?: JSONArray()
                require(notes.length() <= 100000) { "MIDI 音符數量過多" }
                for (n in 0 until notes.length()) {
                    val e = notes.getJSONArray(n)
                    val type = e.getInt(1)
                    val time = e.getInt(0)
                    val note = e.getInt(2)
                    if ((type in NativeEngine.NOTE_ON..NativeEngine.NOTE_OFF ||
                            (type == NativeEngine.CONTROLLER && note == 11)) &&
                        time in 0 until LoopMath.loopTicks(newBars, newNumerator, newDenominator)
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
            cancelAudio()
            clockSender?.invoke(0xfc)
            lastClockIndex = -1L
            playing = false
            clockSender?.invoke(0xfc)
            NativeEngine.midi(NativeEngine.ALL_SOUNDS_OFF, 0, 0, 0)
            armedTrack = null
            recordingTrack = null
            countInActive = false
            preRollEvents.clear()
            heldRecordingNotes.clear()
            heldRecordingNotesByTrack.clear()
            takeHasNotesByTrack.clear()
            pendingExpressionByTrack.clear()
            armedTargets = emptySet()
            recordingTargets = emptySet()
            takeHasNotes = false
            keysDown.clear()
            keyVelocities.clear()
            liveInputOwners.clear()
            physicalKeybed.clear()
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
        metronomeVolume = root.optInt("metronomeVolume", 100).coerceIn(0, 127)
        countInEnabled = root.optBoolean("countInEnabled", true)
        countInBars = root.optInt("countInBars", 1).coerceIn(1, 2)
        selectedId = root.optInt("selectedId", newTracks.firstOrNull()?.id ?: 0)
        nextId = maxOf(root.optInt("nextId", 1), (newTracks.maxOfOrNull { it.id } ?: 0) + 1)
        tracks.clear()
        tracks.addAll(newTracks)
        midiRoutes.clear()
        midiRoutes.addAll(importedRoutes.filter { it.trackId == 0 ||
            tracks.any { track -> track.id == it.trackId } })
        nextMidiRouteId = (midiRoutes.maxOfOrNull { it.id } ?: 0) + 1
        if (tracks.none { it.id == selectedId }) selectedId = tracks.firstOrNull()?.id ?: 0
        inputTargetIds.clear()
        val storedInputs = root.optJSONArray("inputTargetIds")
        if (storedInputs != null) for (i in 0 until storedInputs.length()) {
            val id = storedInputs.optInt(i, 0)
            if (tracks.any { it.id == id }) inputTargetIds += id
        }
        if (inputTargetIds.isEmpty() && selectedId != 0) inputTargetIds += selectedId
        if (importing) {
            refreshPrograms()
            changed()
            hasUnsavedChanges = false
        }
        return true
    }

    /** Parse all routes before mutating the session (JSON import is atomic). */
    private fun decodeMidiRoutes(routeArray: JSONArray): List<MidiRoute> {
        val importedRoutes = mutableListOf<MidiRoute>()
        require(routeArray.length() <= 64) { "MIDI 路由超過上限" }
        val knownActions = setOf("note", "scale", "custom", "drum-pads", "volume", "pan", "master", "metronome-volume", "select-track", "tap-tempo", "tap-record",
            "mute", "solo", "play-stop", "record-toggle", "metronome-toggle", "next-track",
            "slot-volume", "slot-mute", "slot-solo", "mute-all")
        for (index in 0 until routeArray.length()) {
            val item = routeArray.getJSONObject(index)
            val kind = item.optString("kind", "note")
            val originalAction = item.optString("action", "note")
            val first = item.optInt("first")
            val last = item.optInt("last", 127)
            val ch = item.optInt("channel", -1)
            val trackId = item.optInt("trackId")
            val source = item.optString("deviceKey", "*")
            val action = if (originalAction == "metronome-volume") "metronome-toggle" else if (originalAction == "slot-volume" && kind == "cc" &&
                ch == 0 && trackId == 0 && item.optInt("noteBase", -1) == 6 &&
                first == last && first == defaultKnobFirstCc(source) + 6)
                "metronome-toggle" else originalAction
            require(kind in setOf("note", "cc", "program") &&
                action in knownActions && first in 0..127 && last in first..127 &&
                ch in -1..15 && item.optString("deviceKey", "*").length <= 256) {
                "MIDI 路由格式不正確"
            }
            val mapArray = item.optJSONArray("noteMap") ?: JSONArray()
            require((action != "drum-pads" || (kind in setOf("note", "cc") && last - first == 7)) &&
                mapArray.length() <= 16 && (action != "custom" ||
                mapArray.length() == last - first + 1)) { "自訂 MIDI 音符映射長度不符" }
            val map = (0 until mapArray.length()).map { mapArray.getInt(it) }
            require(map.all { it in -1..127 }) { "自訂 MIDI 音高無效" }
            val ccMode = if (action == "metronome-toggle" &&
                originalAction != "metronome-toggle") "absolute"
                else item.optString("ccMode", "legacy")
            require(ccMode in MidiRoute.ccModes) { "CC 觸發模式無效" }
            if (action in setOf("slot-volume", "slot-mute", "slot-solo"))
                require(kind == "cc" && item.optInt("noteBase", -1) in 0..6 && trackId == 0) {
                    "音軌位置必須是 1–7"
                }
            val scaleMode = item.optString("scaleMode", "major")
            require(scaleMode in MidiPitch.modes) { "MIDI 調式無效" }
            importedRoutes += MidiRoute(item.optInt("id", index + 1),
                item.optString("deviceKey", "*"), ch, kind, first, last,
                trackId, action, item.optInt("noteBase", -1).coerceIn(-1, 127),
                scaleMode, map, ccMode)
        }
        return importedRoutes
    }

    /** Independent route preset: does not modify notes, tempo or instrument tracks. */
    @Synchronized fun exportMidiRouting(): String = JSONObject()
        .put("format", "liveperformer.midi-routes.v1")
        .put("midiRoutes", serialize(false, false).getJSONArray("midiRoutes"))
        .toString(2)

    @Synchronized fun importMidiRouting(content: String) {
        require(content.length <= 250_000) { "MIDI 路由 JSON 過大" }
        val root = JSONObject(content)
        require(root.optString("format") == "liveperformer.midi-routes.v1") {
            "不支援的 MIDI 路由 JSON 格式"
        }
        val array = root.optJSONArray("midiRoutes")
            ?: throw IllegalArgumentException("缺少 midiRoutes 陣列")
        val decoded = decodeMidiRoutes(array)
        require(decoded.all { it.trackId == 0 || tracks.any { track -> track.id == it.trackId } }) {
            "匯入路由指定了不存在的音軌"
        }
        require(decoded.map { it.id }.distinct().size == decoded.size) { "MIDI 路由 ID 重複" }
        midiRoutes.clear()
        midiRoutes.addAll(decoded)
        nextMidiRouteId = (decoded.maxOfOrNull { it.id } ?: 0) + 1
        changed()
    }

    @Synchronized fun importDocument(content: String) {
        decode(content, true)
    }

    @Synchronized fun exportDocument(settingsOnly: Boolean): String =
        serialize(settingsOnly, true).toString()

    @Synchronized fun needsSaveBeforeReplace(): Boolean = hasUnsavedChanges

    /** Loading a track template starts a new editable, not-yet-saved project. */
    @Synchronized fun markSessionUnsaved() { hasUnsavedChanges = true }

    @Synchronized fun markSavedIfUnchanged(exportedContent: String) {
        if (exportDocument(false) == exportedContent) hasUnsavedChanges = false
    }


    private fun serialize(settingsOnly: Boolean, fileFormat: Boolean): JSONObject {
        val root = JSONObject()
            .put("bpm", bpm).put("bars", bars)
            .put("beatsPerBar", beatsPerBar).put("beatUnit", beatUnit)
            .put("quantizeDivision", quantizeDivision)
            .put("metronome", metronome).put("metronomeVolume", metronomeVolume)
            .put("countInEnabled", countInEnabled).put("countInBars", countInBars)
            .put("selectedId", selectedId).put("nextId", nextId)
            .put("inputTargetIds", JSONArray(inputTargetIds.toList()))
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
                .put("noteMap", JSONArray(r.noteMap)).put("ccMode", r.ccMode))
        }
        root.put("midiRoutes", routes)
        val saved = JSONArray()
        for (t in tracks) {
            val obj = JSONObject().put("id", t.id).put("channel", t.channel)
                .put("name", t.name).put("bank", t.bank).put("program", t.program)
                .put("muted", t.muted).put("solo", t.solo)
                .put("volume", t.volume).put("keyboardBase", t.keyboardBase)
                .put("fontId", t.fontId).put("category", t.category)
            if (t.isAudio) {
                if (!settingsOnly && t.pcm.isNotEmpty()) obj.put("pcm", LoopPcm.encode(t.pcm))
            } else if (!settingsOnly) {
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
        val stream = runCatching { sessionFile.startWrite() }.getOrElse { return }
        try {
            stream.write(serialize(false, false).toString().toByteArray(Charsets.UTF_8))
            sessionFile.finishWrite(stream)
        } catch (e: Exception) {
            sessionFile.failWrite(stream)
            return
        }
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
            } else if (e.type == NativeEngine.NOTE_OFF ||
                (e.type == NativeEngine.NOTE_ON && e.velocity == 0)) {
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
        val beatSize = LoopMath.beatTicks(beatUnit)
        val countLeft = if (countInActive) kotlin.math.ceil(
            (countInBars * LoopMath.barTicks(beatsPerBar, beatUnit) - countInProgress) / beatSize
        ).toInt().coerceAtLeast(1) else 0
        // Display the physical keybed's MIDI input even if an instrument is
        // muted, pad-only, or not a recording destination.
        val inputVelocities = physicalKeybed.velocities().toMutableMap()
        tracks.filter { it.id in inputTargetIds && !it.isAudio }.forEach { track ->
            keyVelocities[track.channel]?.forEach { (pitch, velocity) ->
                inputVelocities[pitch] = maxOf(inputVelocities[pitch] ?: 0, velocity)
            }
        }
        return LoopView(bpm, bars, beatsPerBar, beatUnit, quantizeDivision, metronome, metronomeVolume, playing, armedTrack != null,
            recordingTrack != null, selectedId, inputTargetIds.toSet(),
            at / LoopMath.barTicks(beatsPerBar, beatUnit) + 1,
            (at / LoopMath.beatTicks(beatUnit)) % beatsPerBar + 1, at.toFloat() / length,
            inputVelocities.keys, inputVelocities,
            tracks.map {
                val recorded = it.events.filter { event -> event.type == NativeEngine.NOTE_ON && event.velocity > 0 }
                LoopTrackView(it.id, it.name, it.channel, it.bank, it.program, it.muted, it.solo,
                    it.volume, it.events.count { event -> event.type == NativeEngine.NOTE_ON },
                    it.fontId, it.category.ifBlank {
                        if (it.channel == 9) "鼓組" else GmCategories.names[GmCategories.family(it.program)]
                    }, pianoNotes(it, length), if (it.id in audioTargets) audioCapture?.peak ?: 0f else if (it.isAudio) it.waveform.getOrNull((at.toFloat() / length / (it.pcm.size.toFloat() / loopSamples().coerceAtLeast(1)).coerceAtLeast(0.0001f) * 128).toInt()) ?: 0f else it.activityLevel, it.keyboardBase,
                    if (recorded.isEmpty()) null else recorded.minOf { e -> e.note }..recorded.maxOf { e -> e.note },
                    it.isAudio, if (it.id in audioTargets) audioCapture?.preview() ?: it.waveform else it.waveform,
                    if (it.id in audioTargets) 1f else (it.pcm.size.toFloat() / loopSamples().coerceAtLeast(1)))
            }, midiRoutes.toList(), countInEnabled, countInBars, countLeft,
            tapTracker.tapCount, audioError)
    }

    private fun publish() {
        mutableState.value = snapshot()
        lastPublishNs = System.nanoTime()
    }
    private fun changed() {
        hasUnsavedChanges = true
        dirty = true
        syncAudio()
        publish()
        // Save is coalesced by the scheduler to avoid a disk write per slider tick.
    }

    private fun advance(now: Long) {
        if (playing) {
            val delta = (now - clockNs).coerceAtLeast(0)
            val increment = delta.toDouble() * bpm * LoopMath.PPQ / 60_000_000_000.0
            if (countInActive) {
                countInProgress += increment
                val duration = countInBars * LoopMath.barTicks(beatsPerBar, beatUnit)
                if (countInProgress >= duration) {
                    countInActive = false
                    absoluteTick = countInProgress - duration
                    lastScheduled = -0.01
                    lastClockIndex = -1L
                    clockSender?.invoke(0xfa)
                }
            } else absoluteTick += increment
        }
        clockNs = now
    }

    /** MIDI Expression automation is independent of CC7 mixer volume and note replacement. */
    private fun captureExpression(track: Track, value: Int, position: Double) {
        val length = LoopMath.loopTicks(bars, beatsPerBar, beatUnit)
        // Do not quantize continuous expression: 4-ms joystick movements should
        // not be rounded onto 1/16-note steps.
        val tick = LoopMath.recordedTick(position, recordStart, length)
        val events = pendingExpressionByTrack.getOrPut(track.id) { mutableListOf() }
        val normalized = value.coerceIn(0, 127)
        if (events.lastOrNull()?.velocity == normalized) return
        val event = Event(tick, NativeEngine.CONTROLLER, 11, normalized)
        if (events.lastOrNull()?.tick == tick) events[events.lastIndex] = event
        else events.add(event)
    }

    /** Finalize a loop pass without disarming; silent passes preserve the old notes. */
    private fun finishRecordingPass(at: Double) {
        for (track in tracks.filter { it.id in recordingTargets && it.id in takeHasNotesByTrack }) {
            val heldForTrack = heldRecordingNotesByTrack[track.id].orEmpty()
            val length = LoopMath.loopTicks(bars, beatsPerBar, beatUnit)
            val end = LoopMath.quantize(at - recordStart, quantizeDivision, length)
            heldForTrack.forEach { note ->
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
        // Replace only the previous expression curve; leave prior notes intact
        // if the user recorded expression without playing new notes.
        val length = LoopMath.loopTicks(bars, beatsPerBar, beatUnit)
        pendingExpressionByTrack.forEach { (id, curve) ->
            val track = tracks.firstOrNull { it.id == id && it.id in recordingTargets }
            if (track != null && curve.isNotEmpty()) {
                track.events.removeAll {
                    it.type == NativeEngine.CONTROLLER && it.note == 11
                }
                track.events += Event(0, NativeEngine.CONTROLLER, 11, 127)
                track.events.addAll(curve)
                if (curve.last().velocity != 127)
                    track.events += Event(length - 1, NativeEngine.CONTROLLER, 11, 127)
                track.events.sortWith(compareBy<Event> { it.tick }.thenByDescending { it.type })
                dirty = true
                hasUnsavedChanges = true
            }
        }
        heldRecordingNotes.clear()
        heldRecordingNotesByTrack.clear()
        takeHasNotesByTrack.clear()
        pendingExpressionByTrack.clear()
        preRollEvents.clear()
        takeHasNotes = false
    }

    private fun stopRecord(at: Double) {
        if (recordingTrack != null) { finishAudio(); finishRecordingPass(at) } else cancelAudio()
        heldRecordingNotes.clear()
        heldRecordingNotesByTrack.clear()
        takeHasNotesByTrack.clear()
        pendingExpressionByTrack.clear()
        preRollEvents.clear()
        recordingTrack = null
        armedTrack = null
        armedTargets = emptySet()
        recordingTargets = emptySet()
        countInActive = false
    }

    private fun capture(track: Track, type: Int, note: Int, velocity: Int, position: Double) {
        if (type == NativeEngine.CONTROLLER && note == 11) {
            captureExpression(track, velocity, position)
            return
        }
        val length = LoopMath.loopTicks(bars, beatsPerBar, beatUnit)
        if (type == NativeEngine.NOTE_ON && velocity > 0 && track.id !in takeHasNotesByTrack) {
            // Replace, never overdub. Keep the previous recording through an empty pass.
            if (track.events.isNotEmpty()) {
                // Punch-in must silence the old take immediately, before this NoteOn.
                NativeEngine.midi(NativeEngine.CONTROLLER, track.channel, 64, 0)
                NativeEngine.midi(NativeEngine.CONTROLLER, track.channel, 120, 0)
                keysDown.remove(track.channel)
                keyVelocities.remove(track.channel)
                track.heldVelocities.clear()
            }
            track.events.clear()
            // An old expression curve might have left this synth channel quiet.
            // Reset only when no new live CC11 gesture has been captured.
            if (pendingExpressionByTrack[track.id].isNullOrEmpty())
                NativeEngine.midi(NativeEngine.CONTROLLER, track.channel, 11, 127)
            heldRecordingNotesByTrack.remove(track.id)
            // Do not cut off a live note that began just before the downbeat.
            takeHasNotesByTrack += track.id
        }
        if (track.id !in takeHasNotesByTrack) return
        val normalizedType = if (type == NativeEngine.NOTE_ON && velocity == 0)
            NativeEngine.NOTE_OFF else type
        val held = heldRecordingNotesByTrack.getOrPut(track.id) { mutableSetOf() }
        if (normalizedType == NativeEngine.NOTE_OFF && note !in held) return
        val tick = LoopMath.quantize(position - recordStart, quantizeDivision, length,
            wrap = normalizedType == NativeEngine.NOTE_ON)
        val safeTick = if (normalizedType == NativeEngine.NOTE_OFF) {
            val prior = track.events.lastOrNull {
                it.type == NativeEngine.NOTE_ON && it.note == note
            }?.tick ?: 0
            tick.coerceAtLeast(prior + 1).coerceAtMost(length - 1)
        } else tick
        track.events += Event(safeTick, normalizedType, note, velocity.coerceIn(0, 127))
        if (normalizedType == NativeEngine.NOTE_ON) held.add(note)
        else held.remove(note)
        dirty = true
        hasUnsavedChanges = true
    }

    private fun noteActivity(track: Track, type: Int, note: Int, velocity: Int) {
        if (type == NativeEngine.NOTE_ON && velocity > 0) {
            track.heldVelocities[note] = velocity
            track.activityLevel = maxOf(track.activityLevel, velocity / 127f)
        } else if (type == NativeEngine.NOTE_OFF || type == NativeEngine.NOTE_ON && velocity == 0) {
            track.heldVelocities.remove(note)
        }
    }

    private fun resetActivity() {
        tracks.forEach { t -> t.heldVelocities.clear(); t.activityLevel = 0f }
    }

    private fun decayActivity() {
        tracks.forEach { t ->
            val held = (t.heldVelocities.values.maxOrNull() ?: 0) / 127f
            t.activityLevel = maxOf(held, t.activityLevel * 0.88f)
        }
    }

    private fun displayKey(channel: Int, type: Int, note: Int, velocity: Int) {
        if (type == NativeEngine.NOTE_ON && velocity > 0) {
            keysDown.getOrPut(channel) { mutableSetOf() }.add(note)
            keyVelocities.getOrPut(channel) { mutableMapOf() }[note] = velocity.coerceIn(1, 127)
        } else if (type == NativeEngine.NOTE_OFF || (type == NativeEngine.NOTE_ON && velocity == 0)) {
            keysDown[channel]?.remove(note)
            keyVelocities[channel]?.remove(note)
        }
    }

    private fun updateRecording() {
        // Waiting for PLAY or the fourth TAP must never become recording while stopped.
        if (!playing || countInActive) return
        if (armedTrack != null && absoluteTick >= armedAt) {
            recordingTrack = armedTrack
            recordingTargets = armedTargets
            armedTargets = emptySet()
            recordStart = armedAt
            audioTargets = if (audioCapture != null) tracks.filter { it.isAudio && it.id in recordingTargets }.map { it.id }.toSet() else emptySet()
            if (audioTargets.isNotEmpty()) {
                val elapsed = ((absoluteTick - recordStart) * 60_000_000_000.0 / (bpm * LoopMath.PPQ)).toLong()
                audioCapture?.begin(clockNs - elapsed, loopSamples())
            }
            armedTrack = null
            heldRecordingNotesByTrack.clear()
            takeHasNotesByTrack.clear()
            pendingExpressionByTrack.clear()
            // Pick up a downbeat played slightly early, within half a quantization step.
            preRollEvents.forEach { e ->
                tracks.firstOrNull { it.id == e.trackId && it.id in recordingTargets }?.let { track ->
                    capture(track, e.type, e.note, e.velocity, e.position)
                }
            }
            preRollEvents.clear()
        }
        // Finalize at every boundary but stay in Record. The next pass only
        // replaces existing content when its first new NoteOn arrives.
        if (recordingTrack != null) {
            val length = LoopMath.loopTicks(bars, beatsPerBar, beatUnit)
            while (absoluteTick >= recordStart + length) {
                finishAudio()
                finishRecordingPass(recordStart + length - 1)
                recordStart += length
                recordingTargets = recordingTargets.filterTo(linkedSetOf()) { id ->
                    tracks.any { it.id == id && !it.isAudio }
                }
                recordingTrack = recordingTargets.firstOrNull()
                if (recordingTrack == null) break
            }
        }
    }

    @Synchronized private fun tick() {
        val now = System.nanoTime()
        advance(now)
        updateRecording()
        audioCapture?.error?.let { audioError = it; cancelAudio() }
        syncAudio()
        if (countInActive) {
            // Count-in clicks continue even when the ordinary metronome is switched off.
            emitClicks(lastCountInScheduled, countInProgress, true)
            lastCountInScheduled = countInProgress
            if (now - lastPublishNs > 90_000_000L) { decayActivity(); publish() }
            if (dirty && now - lastSaveNs > 2_000_000_000L) save()
            return
        }
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
            for (track in tracks) for (e in track.events) {
                val occurrence = LoopMath.nextOccurrence(lastScheduled, e.tick, length)
                if (occurrence <= absoluteTick && occurrence > absoluteTick - length) {
                    noteActivity(track, e.type, e.note, e.velocity)
                    if (!track.muted && (!anySolo || track.solo) &&
                        !(track.id in recordingTargets && track.id in takeHasNotesByTrack) &&
                        !(track.id in recordingTargets &&
                            pendingExpressionByTrack[track.id]?.isNotEmpty() == true &&
                            e.type == NativeEngine.CONTROLLER && e.note == 11)) {
                        sendToSynth(track, e.type, e.note, e.velocity)
                        displayKey(track.channel, e.type, e.note, e.velocity)
                    }
                }
            }
            if (metronome) emitClicks(lastScheduled, absoluteTick, false)
            skipInitialTapClick = false
            lastScheduled = absoluteTick
        }
        if (now - lastPublishNs > 90_000_000L) { decayActivity(); publish() }
        if (dirty && now - lastSaveNs > 2_000_000_000L) save()
    }

    /** Shared click scheduler; count-in uses the same accent and beat unit as playback. */
    private fun emitClicks(from: Double, to: Double, force: Boolean) {
        if (!metronome && !force) return
        val beat = LoopMath.beatTicks(beatUnit)
        val firstBeat = (floor(from / beat) + 1) * beat
        if (firstBeat <= to) {
            val index = (firstBeat / beat).toLong()
            if (!(skipInitialTapClick && index == 0L))
                NativeEngine.midi(NativeEngine.METRONOME_CLICK, 0,
                    if (index % beatsPerBar == 0L) 1 else 0, 0)
        }
    }

    /** Transport has Play/Stop semantics, never resume-from-middle. */
    @Synchronized fun playStop() {
        val now = System.nanoTime()
        advance(now)
        if (playing) {
            if (recordingTrack != null || armedTrack != null) stopRecord(absoluteTick)
            playing = false
            countInActive = false
            NativeEngine.midi(NativeEngine.ALL_SOUNDS_OFF, 0, 0, 0)
            // A stopped take must never leave CC11 attenuation on a channel.
            tracks.filterNot { it.isAudio }.forEach {
                NativeEngine.midi(NativeEngine.CONTROLLER, it.channel, 11, 127)
            }
            liveInputOwners.clear()
            physicalKeybed.clear()
            keysDown.clear()
            keyVelocities.clear()
            resetActivity()
        } else {
            playing = true
            countInActive = armedTrack != null && countInEnabled
            countInProgress = 0.0
            lastCountInScheduled = -0.01
            if (!countInActive) clockSender?.invoke(0xfa)
        }
        lastClockIndex = -1L
        absoluteTick = 0.0
        lastScheduled = -0.01
        skipInitialTapClick = false
        clockNs = now
        // Starting without count-in should record on the very same PLAY press,
        // not depend on the next 4-ms scheduler dispatch to arm the take.
        if (playing && !countInActive) updateRecording()
        syncAudio()
        publish()
        save()
    }

    @Synchronized fun playPause() = playStop()

    /** Arm is non-destructive and does not start transport by itself. */
    @Synchronized fun recordPause() {
        if (armedTrack == null && recordingTrack == null &&
            tracks.none { it.id in inputTargetIds }) return
        advance(System.nanoTime())
        if (recordingTrack != null || armedTrack != null) {
            stopRecord(absoluteTick)
            changed()
            return
        }
        if (audioPermissionNeeded()) { audioError = "請授予麥克風錄音權限"; publish(); return }
        audioError = null
        if (tracks.any { it.isAudio && it.id in inputTargetIds }) {
            cancelAudio()
            audioCapture = AudioLoopCapture().also { it.open() }
        }
        preRollEvents.clear()
        takeHasNotes = false
        heldRecordingNotesByTrack.clear()
        takeHasNotesByTrack.clear()
        pendingExpressionByTrack.clear()
        armedAt = if (playing) LoopMath.nextBoundary(absoluteTick,
            LoopMath.loopTicks(bars, beatsPerBar, beatUnit)) else 0.0
        armedTargets = inputTargetIds.filterTo(linkedSetOf()) { id -> tracks.any { it.id == id } }
        armedTrack = armedTargets.firstOrNull()
        if (playing) updateRecording()
        publish()
    }

    @Synchronized fun clearSelected() = clearTrack(selectedId)

    @Synchronized fun clearTrack(id: Int) {
        val t = tracks.firstOrNull { it.id == id } ?: return
        if (t.id in recordingTargets || t.id in armedTargets) stopRecord(absoluteTick)
        t.events.clear()
        if (t.isAudio) t.replacePcm(ShortArray(0))
        releaseLiveTrack(t.id)
        changed()
    }

    /** Pad routes stay armed while keybed input is restricted to melody tracks. */
    private fun keyboardDestinations(sourceIsMpk: Boolean): Set<Int> =
        InputRoutingPolicy.keyboardDestinations(
            inputTargetIds.filterTo(linkedSetOf()) { id -> tracks.any { it.id == id && !it.isAudio } }, midiRoutes, sourceIsMpk,
            tracks.firstOrNull(::isDrumTrack)?.id,
            tracks.firstOrNull {
                it.name.contains("Bass", ignoreCase = true) ||
                    it.name.contains("貝斯") || it.category == "貝斯"
            }?.id
        )

    /** Note release follows the original track even when focus has changed. */
    private fun dispatchLiveInput(
        source: String, type: Int, note: Int, velocity: Int, sourceIsMpk: Boolean = false
    ) {
        val noteType = if (type == NativeEngine.NOTE_ON && velocity == 0)
            NativeEngine.NOTE_OFF else type
        val key = "${source}:${note}"
        val destinations: Set<Int> = when (noteType) {
            NativeEngine.NOTE_ON -> {
                val ids = keyboardDestinations(sourceIsMpk)
                liveInputOwners.remove(key)?.forEach { id ->
                    tracks.firstOrNull { it.id == id }?.let {
                        dispatchToTrack(it, NativeEngine.NOTE_OFF, note, 0)
                    }
                }
                if (ids.isNotEmpty()) liveInputOwners[key] = ids.toMutableSet()
                ids
            }
            NativeEngine.NOTE_OFF -> liveInputOwners.remove(key).orEmpty()
            else -> keyboardDestinations(sourceIsMpk)
        }
        tracks.filter { it.id in destinations }.forEach {
            dispatchToTrack(it, noteType, note, velocity)
        }
    }

    /** Touch keyboard plays the selected input destinations. */
    @Synchronized fun onMidi(type: Int, originalChannel: Int, a: Int, b: Int) {
        dispatchLiveInput("touch:${originalChannel}", type, a, b)
    }

    /** Stop only this channel, not the other tracks or the entire synthesizer. */
    private fun releaseLiveTrack(id: Int) {
        val track = tracks.firstOrNull { it.id == id } ?: return
        if (track.isAudio) return
        advance(System.nanoTime())
        updateRecording()
        heldRecordingNotesByTrack[id]?.toList()?.forEach { note ->
            capture(track, NativeEngine.NOTE_OFF, note, 0, absoluteTick)
        }
        if (id in recordingTargets && pendingExpressionByTrack[id]?.isNotEmpty() == true)
            capture(track, NativeEngine.CONTROLLER, 11, 127, absoluteTick)
        NativeEngine.midi(NativeEngine.CONTROLLER, track.channel, 64, 0)
        NativeEngine.midi(NativeEngine.CONTROLLER, track.channel, 120, 0)
        NativeEngine.midi(NativeEngine.CONTROLLER, track.channel, 11, 127)
        keysDown.remove(track.channel)
        keyVelocities.remove(track.channel)
        track.heldVelocities.clear()
        track.activityLevel = 0f
        val iterator = liveInputOwners.iterator()
        while (iterator.hasNext()) {
            val owner = iterator.next()
            owner.value.remove(id)
            if (owner.value.isEmpty()) iterator.remove()
        }
    }

    private fun releaseDepartedInputs(previous: Set<Int>, current: Set<Int>) {
        (previous - current).forEach(::releaseLiveTrack)
    }

    /** Numbered track handles toggle additional input targets without changing the focused track. */
    @Synchronized fun toggleInputTarget(id: Int) {
        if (tracks.none { it.id == id }) return
        if (!inputTargetIds.add(id)) {
            inputTargetIds.remove(id)
            releaseLiveTrack(id)
        }
        changed()
    }

    private fun isDrumTrack(track: Track): Boolean =
        track.channel == 9 || track.bank >= 128 || track.category == "鼓組"

    private fun sendToSynth(track: Track, type: Int, canonical: Int, velocity: Int) {
        if (type == NativeEngine.CONTROLLER || type == NativeEngine.PITCH_BEND) {
            NativeEngine.midi(type, track.channel, canonical, velocity)
            return
        }
        val drumMap = if (isDrumTrack(track)) drumMaps.forPreset(track.fontId, track.bank, track.program) else null
        // SoundFont exclusiveClass is authoritative. NoteOff is a helpful fallback
        // for open-hat samples that respond to release; some one-shots ignore it.
        if (drumMap != null && type == NativeEngine.NOTE_ON && velocity > 0 && canonical == 42)
            NativeEngine.midi(NativeEngine.NOTE_OFF, track.channel,
                drumMap.outputNote(46), 0)
        NativeEngine.midi(type, track.channel,
            drumMap?.outputNote(canonical) ?: canonical, velocity)
    }

    /** Record canonical GM drum roles, translate only when sending to synth. */
    private fun dispatchToTrack(track: Track, type: Int, a: Int, b: Int) {
        if (track.isAudio) {
            if (type == NativeEngine.CONTROLLER && a == 7) setTrackVolume(track.id, b)
            return
        }
        if (type == NativeEngine.CONTROLLER && a == 7) {
            setTrackVolume(track.id, b)
            return
        }
        advance(System.nanoTime())
        updateRecording()
        if (type == NativeEngine.NOTE_ON || type == NativeEngine.NOTE_OFF ||
            (type == NativeEngine.CONTROLLER && a == 11)) {
            val note = a.coerceIn(0, 127)
            val velocity = b.coerceIn(0, 127)
            if (track.id in recordingTargets) {
                capture(track, type, note, velocity, absoluteTick)
            } else if (countInActive && track.id in armedTargets &&
                LoopMath.isCountInPreRoll(countInProgress,
                    countInBars * LoopMath.barTicks(beatsPerBar, beatUnit), quantizeDivision)) {
                preRollEvents += Captured(track.id,
                    countInProgress - countInBars * LoopMath.barTicks(beatsPerBar, beatUnit),
                    type, note, velocity)
            } else if (!countInActive && track.id in armedTargets &&
                absoluteTick >= armedAt - LoopMath.preRollTicks(quantizeDivision) &&
                absoluteTick < armedAt) {
                preRollEvents += Captured(track.id, absoluteTick, type, note, velocity)
            }
        }
        if (type == NativeEngine.NOTE_ON || type == NativeEngine.NOTE_OFF)
            displayKey(track.channel, type, a, b)
        noteActivity(track, type, a, b)
        if (!track.muted && (tracks.none { it.solo } || track.solo))
            sendToSynth(track, type, a, b)
        if (type == NativeEngine.NOTE_ON || type == NativeEngine.NOTE_OFF) publish()
    }

    /** MPK-only defaults work before any per-project routes have been saved. */
    private fun defaultMpkCcRoute(event: MidiEvent): MidiRoute? {
        if (event.kind != 0xb0 ||
            !event.deviceName.contains("MPK", ignoreCase = true)) return null
        val number = event.data1
        val sameDevice: (MidiRoute) -> Boolean = {
            it.deviceKey == "*" || it.deviceKey == event.deviceKey
        }
        if (event.channel == 1 && number in 20..35) {
            val hasEditedPadBank = midiRoutes.any {
                sameDevice(it) && it.kind == "cc" && it.channel in setOf(-1, 1) &&
                    it.first <= 35 && it.last >= 20
            }
            if (hasEditedPadBank) return null
            val (action, slot) = when (number) {
                20 -> "play-stop" to -1
                21 -> "record-toggle" to -1
                22 -> "metronome-toggle" to -1
                in 23..27 -> "slot-solo" to (number - 23)
                in 28..34 -> "slot-mute" to (number - 28)
                else -> "mute-all" to -1
            }
            return MidiRoute(-1, event.deviceKey, 1, "cc", number, number, 0,
                action, slot, ccMode = if (number <= 27) "press-toggle" else "absolute")
        }
        val firstKnob = defaultKnobFirstCc(event.deviceKey)
        if (event.channel == 0 && number in firstKnob..(firstKnob + 7)) {
            val hasEditedKnobs = midiRoutes.any {
                sameDevice(it) && it.kind == "cc" && it.channel in setOf(-1, 0) &&
                    it.action in setOf("slot-volume", "metronome-volume", "metronome-toggle", "master")
            }
            if (hasEditedKnobs) return null
            val slot = number - firstKnob
            return MidiRoute(-1, event.deviceKey, 0, "cc", number, number, 0,
                when (slot) {
                    in 0..5 -> "slot-volume"
                    6 -> "metronome-toggle"
                    else -> "master"
                }, if (slot < 6) slot else -1,
                ccMode = if (slot == 6) "absolute" else "legacy")
        }
        return null
    }

    /** Physical source channel is independent from the synth's assigned track channel. */
    @Synchronized fun onExternalMidi(event: MidiEvent) {
        if (physicalKeybed.accept(event)) publish()
        val configured = midiRoutes.filter { it.matches(event) }
        val routes = if (configured.isNotEmpty()) configured
            else defaultMpkCcRoute(event)?.let { listOf(it) } ?: emptyList()
        // MPK down joystick uses 0 at rest, 127 pushed down. Standard CC11
        // uses 127 for full expression and 0 for silence: invert at the input
        // boundary, then record/replay real standard MIDI CC11 values.
        if (JoystickExpressionPolicy.needsNeutralExpression(event, routes.isNotEmpty())) {
            val expression = JoystickExpressionPolicy.expressionValue(event.data2)
            tracks.filter { it.id in keyboardDestinations(true) }.forEach {
                dispatchToTrack(it, NativeEngine.CONTROLLER, 11, expression)
            }
            return
        }
        if (routes.isNotEmpty()) {
            for (route in routes) {
                val track = tracks.firstOrNull { it.id == route.trackId }
                when (route.action) {
                    "note", "scale", "custom", "drum-pads" -> if (track != null) {
                        val result = route.noteFor(event) ?: continue
                        // Treat the historical MPK 36..43 raw route as fixed drum pads.
                        val legacyMpk = isDrumTrack(track) && route.action == "note" &&
                            route.kind == "note" && route.noteBase == -1 &&
                            route.first == 36 && route.last == 43 &&
                            event.deviceName.contains("MPK", ignoreCase = true)
                        val canonical = if (legacyMpk)
                            DrumPads.canonicalForPad(event.data1) ?: result.first
                            else result.first
                        dispatchToTrack(track, if (result.second > 0)
                            NativeEngine.NOTE_ON else NativeEngine.NOTE_OFF,
                            canonical, result.second)
                    }
                    "volume", "pan", "slot-volume" -> {
                        val target = if (route.action == "slot-volume")
                            tracks.getOrNull(route.noteBase) else track
                        if (target != null) {
                            if (route.action == "pan" && !target.isAudio) NativeEngine.midi(
                                NativeEngine.CONTROLLER, target.channel, 10, event.data2)
                            else if (route.action != "pan") setTrackVolume(target.id, event.data2)
                        }
                    }
                    "metronome-volume" -> setMetronome(event.data2 >= 64)
                    "master" -> masterVolumeFromMidi?.invoke(
                        event.data2.coerceIn(0, 127) / 127f * 1.5f)
                    "mute", "slot-mute" -> {
                        val target = if (route.action == "slot-mute")
                            tracks.getOrNull(route.noteBase) else track
                        if (target != null) route.targetState(event, target.muted)?.let {
                            setTrackMuted(target.id, it)
                        }
                    }
                    "solo", "slot-solo" -> {
                        val target = if (route.action == "slot-solo")
                            tracks.getOrNull(route.noteBase) else track
                        if (target != null) route.targetState(event, target.solo)?.let {
                            setTrackSolo(target.id, it)
                        }
                    }
                    "mute-all" -> route.targetState(event,
                        tracks.isNotEmpty() && tracks.all { it.muted })?.let { target ->
                        tracks.forEach { setTrackMuted(it.id, target) }
                    }
                    "play-stop" -> route.targetState(event, playing)?.let { target ->
                        if (target != playing) playStop()
                    }
                    "record-toggle" -> route.targetState(event,
                        recordingTrack != null || armedTrack != null)?.let { target ->
                        if (target != (recordingTrack != null || armedTrack != null)) recordPause()
                    }
                    "tap-tempo", "tap-record" -> if (event.kind == 0xb0 && event.data2 >= 64)
                        tapTempo()
                    "metronome-toggle" -> route.targetState(event, metronome)?.let {
                        if (it != metronome) setMetronome(it)
                    }
                    // One-shot actions remain press-only, independent of physical latch mode.
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
        // MPK Note Bank B stays Bass even in projects without saved routing.
        if (event.channel == 1 && event.deviceName.contains("MPK", ignoreCase = true) &&
            event.kind in setOf(0x80, 0x90) && event.data1 in 44..51) {
            val bass = tracks.firstOrNull {
                it.name.contains("Bass", ignoreCase = true) || it.name.contains("貝斯") ||
                    it.category == "貝斯"
            }
            if (bass != null) {
                val velocity = if (event.kind == 0x90) event.data2 else 0
                dispatchToTrack(bass, if (velocity > 0) NativeEngine.NOTE_ON
                    else NativeEngine.NOTE_OFF,
                    MidiPitch.scale(36, event.data1 - 44, "major"), velocity)
                return
            }
        }
        // MPK Bank A also works in projects with no explicit MIDI routes.
        if (event.channel == 1 && event.deviceName.contains("MPK", ignoreCase = true) &&
            event.kind in setOf(0x80, 0x90) &&
            event.data1 in DrumPads.FIRST_MPK_NOTE..DrumPads.LAST_MPK_NOTE) {
            tracks.firstOrNull(::isDrumTrack)?.let { drum ->
                val velocity = if (event.kind == 0x90) event.data2 else 0
                dispatchToTrack(drum, if (velocity > 0) NativeEngine.NOTE_ON
                    else NativeEngine.NOTE_OFF,
                    DrumPads.canonicalForPad(event.data1)!!, velocity)
                return
            }
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
        val source = "midi:${event.deviceKey}:${event.deviceId}:${event.port}:${event.channel}"
        val isMpkKeybed = event.channel == 0 &&
            event.deviceName.contains("MPK", ignoreCase = true)
        when (event.kind) {
            0x80 -> dispatchLiveInput(source, NativeEngine.NOTE_OFF,
                event.data1, event.data2, isMpkKeybed)
            0x90 -> dispatchLiveInput(source,
                if (event.data2 == 0) NativeEngine.NOTE_OFF else NativeEngine.NOTE_ON,
                event.data1, event.data2, isMpkKeybed)
            0xb0 -> if (event.channel == 0 || midiRoutes.isEmpty())
                tracks.filter { it.id in keyboardDestinations(isMpkKeybed) }.forEach {
                    dispatchToTrack(it, NativeEngine.CONTROLLER, event.data1, event.data2)
                }
            0xe0 -> tracks.filter { it.id in keyboardDestinations(isMpkKeybed) }.forEach {
                dispatchToTrack(it, NativeEngine.PITCH_BEND, 0,
                    event.data1 or (event.data2 shl 7))
            }
            // Program Change is diagnostic-only by default: it must not silently
            // overwrite the SoundFont preset chosen by the project.
        }
    }

    /** Atomic create/update: saving an edited pad bank never duplicates its old rule. */
    @Synchronized fun putMidiRoute(
        id: Int?, deviceKey: String, channel: Int, kind: String, first: Int, last: Int,
        trackId: Int, action: String, noteBase: Int = -1,
        scaleMode: String = "major", noteMap: List<Int> = emptyList(),
        ccMode: String = "legacy"
    ): Boolean {
        if ((id == null && midiRoutes.size >= 48) || (id != null && midiRoutes.none { it.id == id }) ||
            deviceKey.length > 256 || channel !in -1..15 ||
            kind !in setOf("note", "cc", "program") ||
            action !in setOf("note", "scale", "custom", "drum-pads", "volume", "pan", "master", "metronome-volume", "select-track", "tap-tempo", "tap-record",
                "mute", "solo", "play-stop", "record-toggle", "metronome-toggle", "next-track",
                "slot-volume", "slot-mute", "slot-solo", "mute-all") ||
            (kind == "program" && action != "select-track") ||
            (kind != "program" && action == "select-track") ||
            (action in setOf("volume", "pan", "master", "metronome-volume", "mute", "solo",
                "play-stop", "record-toggle", "metronome-toggle", "next-track", "tap-tempo", "tap-record",
                "slot-volume", "slot-mute", "slot-solo", "mute-all") && kind != "cc") ||
            first !in 0..127 || last !in first..127 ||
            (trackId != 0 && tracks.none { it.id == trackId }) ||
            (trackId == 0) != (action in setOf("master", "metronome-volume", "play-stop", "record-toggle",
                "metronome-toggle", "next-track", "tap-tempo", "tap-record", "slot-volume", "slot-mute", "slot-solo", "mute-all")) ||
            noteBase !in -1..127 || scaleMode !in MidiPitch.modes ||
            ccMode !in MidiRoute.ccModes ||
            (action in setOf("slot-volume", "slot-mute", "slot-solo") && noteBase !in 0..6) ||
            (action == "scale" && noteBase < 0) ||
            (action == "drum-pads" && (kind !in setOf("note", "cc") || last - first != 7)) ||
            (action == "custom" && (last - first + 1 > 16 ||
                noteMap.size != last - first + 1 || noteMap.any { it !in -1..127 })))
            return false
        val candidate = MidiRoute(id ?: nextMidiRouteId++, deviceKey, channel, kind,
            first, last, trackId, action, noteBase, scaleMode,
            if (action == "custom") noteMap.toList() else emptyList(), ccMode)
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

    /** MPK channel 2 Note 36–43 are fixed musical roles, not pitches. */
    @Synchronized fun applyMpkMiniMk2Preset(deviceKey: String): Boolean {
        val drums = tracks.firstOrNull(::isDrumTrack) ?: return false
        val existing = midiRoutes.firstOrNull {
            (it.deviceKey == deviceKey || it.deviceKey == "*") &&
                (it.channel == 1 || it.channel == -1) &&
                it.kind == "note" && it.first <= 43 && it.last >= 36
        }
        if (existing != null) {
            // Upgrade the historical passthrough; never replace user-customized routes.
            if (existing.first != 36 || existing.last != 43 ||
                existing.trackId != drums.id) return false
            if (existing.action != "drum-pads" &&
                (existing.action != "note" || existing.noteBase != -1)) return false
            if (existing.action != "drum-pads" &&
                !putMidiRoute(existing.id, existing.deviceKey, existing.channel,
                    "note", 36, 43, drums.id, "drum-pads")) return false
        } else if (!putMidiRoute(null, deviceKey, 1, "note",
                36, 43, drums.id, "drum-pads")) return false

        // Note Bank B is always Bass (one octave of chosen major/minor key).
        val bass = tracks.firstOrNull {
            it.id != drums.id && (it.name.contains("Bass", true) ||
                it.name.contains("貝斯") || it.category == "貝斯")
        }
        if (bass != null) {
            val existingBass = midiRoutes.firstOrNull {
                (it.deviceKey == deviceKey || it.deviceKey == "*") &&
                    (it.channel == 1 || it.channel == -1) &&
                    it.kind == "note" && it.first <= 51 && it.last >= 44
            }
            if (existingBass == null) {
                if (!putMidiRoute(null, deviceKey, 1, "note", 44, 51,
                        bass.id, "scale", 36, "major")) return false
            } else if (existingBass.first == 44 && existingBass.last == 51 &&
                existingBass.trackId == bass.id && existingBass.action == "note" &&
                existingBass.noteBase == -1) {
                // Migrate only the unmodified old passthrough default.
                if (!putMidiRoute(existingBass.id, existingBass.deviceKey,
                        existingBass.channel, "note", 44, 51, bass.id,
                        "scale", 36, "major")) return false
            }
            // Existing custom key, scale or individual note map is preserved.
        }
        return true
    }

    /** Global per-font mapping is independent of project serialization. */
    @Synchronized fun drumMapName(fontId: String): String = drumMaps.description(fontId)

    @Synchronized fun importDrumMap(fontId: String, json: String): String {
        require(fontIndexes.containsKey(fontId)) { "找不到目標音色庫" }
        val map = drumMaps.install(fontId, json)
        NativeEngine.midi(NativeEngine.ALL_SOUNDS_OFF, 0, 0, 0)
        publish()
        return map.name
    }

    @Synchronized fun resetDrumMap(fontId: String) {
        drumMaps.reset(fontId)
        NativeEngine.midi(NativeEngine.ALL_SOUNDS_OFF, 0, 0, 0)
        publish()
    }

    /**
     * One button replaces MPK pad CC banks (channel 2, CC20–35) and
     * knobs (keyboard/control channel 1, default CC 2–9).
     * Hardware CC Bank A is Momentary, interpreted as software toggle on
     * each press; hardware CC Bank B is Toggle (127=on, 0=off).
     * The user's real MPK mini MKII confirms Note A/B remains playable.
     */
    @Synchronized fun applyMpkMiniMk2ControlsPreset(
        deviceKey: String, firstKnobCc: Int = 2
    ): Boolean {
        if (firstKnobCc !in 0..120 || deviceKey.isBlank()) return false
        fun matchesDevice(r: MidiRoute) =
            r.deviceKey == deviceKey || r.deviceKey == "*"
        val remainder = midiRoutes.filterNot { r ->
            matchesDevice(r) && r.kind == "cc" &&
                ((r.channel in setOf(-1, 1) && r.first <= 35 && r.last >= 20) ||
                 (r.channel in setOf(-1, 0) &&
                     (r.action in setOf("slot-volume", "metronome-volume", "master") ||
                       (r.action == "metronome-toggle" && r.trackId == 0 &&
                        r.first == r.last && r.first !in 20..35) ||
                      (r.first <= firstKnobCc + 7 && r.last >= firstKnobCc))))
        }
        // Slot targets are positions in the current performance track order.
        val assignments = mutableListOf<Triple<Int, String, Int>>()
        assignments += Triple(20, "play-stop", -1)
        assignments += Triple(21, "record-toggle", -1)
        assignments += Triple(22, "metronome-toggle", -1)
        for (slot in 0..4) assignments += Triple(23 + slot, "slot-solo", slot)
        for (slot in 0..6) assignments += Triple(28 + slot, "slot-mute", slot)
        assignments += Triple(35, "mute-all", -1)
        val proposed = mutableListOf<MidiRoute>()
        var next = nextMidiRouteId
        for ((number, action, slot) in assignments)
            proposed += MidiRoute(next++, deviceKey, 1, "cc", number, number,
                0, action, slot, ccMode = if (number <= 27) "press-toggle" else "absolute")
        for (slot in 0..5)
            proposed += MidiRoute(next++, deviceKey, 0, "cc",
                firstKnobCc + slot, firstKnobCc + slot, 0, "slot-volume", slot)
        proposed += MidiRoute(next++, deviceKey, 0, "cc",
            firstKnobCc + 6, firstKnobCc + 6, 0, "metronome-toggle",
            ccMode = "absolute")
        proposed += MidiRoute(next++, deviceKey, 0, "cc",
            firstKnobCc + 7, firstKnobCc + 7, 0, "master")
        if (remainder.size + proposed.size > 48) return false
        midiRoutes.clear()
        midiRoutes.addAll(remainder)
        midiRoutes.addAll(proposed)
        nextMidiRouteId = next
        mpkPrefs.edit().putInt("knobFirstV2:$deviceKey", firstKnobCc).apply()
        changed()
        return true
    }

    /** Deprecated old CC Bass shortcut: use Note Bank B instead. */
    @Synchronized fun applyMpkMiniMk2CcBassPreset(deviceKey: String): Boolean = false

    /** Deprecated Bank B mixture; all 16 CC pads now have switch functions. */
    @Synchronized fun applyMpkMiniMk2StagePreset(deviceKey: String): Boolean =
        applyMpkMiniMk2ControlsPreset(deviceKey)

    /** Tap always sounds; fourth tap starts an armed stopped take, without count-in. */
    @Synchronized fun tapTempo(nowMillis: Long = android.os.SystemClock.elapsedRealtime()): Int? {
        val accent = tapTracker.tapCount == 0 || nowMillis - lastTapMillis > 2_000L
        NativeEngine.midi(NativeEngine.METRONOME_CLICK, 0, if (accent) 1 else 0, 0)
        lastTapMillis = nowMillis
        val result = tapTracker.tap(nowMillis)
        if (result != null) {
            setBpm(result)
            if (armedTrack != null && !playing) startRecordingFromTap()
        }
        publish()
        return result
    }

    private fun startRecordingFromTap() {
        if (armedTrack == null) return
        absoluteTick = 0.0
        // Preserve tick-zero notes of existing tracks while avoiding duplicate click.
        lastScheduled = -0.01
        skipInitialTapClick = true
        clockNs = System.nanoTime()
        playing = true
        countInActive = false
        countInProgress = 0.0
        lastClockIndex = -1L
        clockSender?.invoke(0xfa)
        armedAt = 0.0
        updateRecording()
    }

    @Synchronized fun setBpm(value: Int) {
        advance(System.nanoTime())
        if (audioCapture != null) stopRecord(absoluteTick)
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
        countInActive = false
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
    @Synchronized fun setMetronomeVolume(value: Int) {
        val next = value.coerceIn(0, 127)
        if (metronomeVolume == next) return
        metronomeVolume = next
        changed()
    }
    @Synchronized fun setCountInEnabled(value: Boolean) {
        countInEnabled = value
        changed()
    }
    @Synchronized fun setCountInBars(value: Int) {
        countInBars = value.coerceIn(1, 2)
        changed()
    }
    @Synchronized fun setTrackVolume(id: Int, value: Int) {
        val t = tracks.firstOrNull { it.id == id } ?: return
        val next = value.coerceIn(0, 127)
        if (t.volume == next) return
        t.volume = next
        if (!t.isAudio) NativeEngine.midi(NativeEngine.CONTROLLER, t.channel, 7, next)
        changed()
    }
    @Synchronized fun setKeyboardBase(id: Int, value: Int) {
        val t = tracks.firstOrNull { it.id == id } ?: return
        t.keyboardBase = value.coerceIn(0, 127)
        changed()
    }
    @Synchronized fun selectTrack(id: Int) {
        if (tracks.any { it.id == id }) {
            val previous = inputTargetIds.toSet()
            selectedId = id
            inputTargetIds.clear()
            inputTargetIds += id
            releaseDepartedInputs(previous, inputTargetIds)
            changed()
        }
    }
    @Synchronized fun addAudioTrack(): Boolean {
        val channel = (-1 downTo -8).firstOrNull { c -> tracks.none { it.channel == c } } ?: return false
        val id = nextId++
        tracks += Track(id, channel, "音訊 ${-channel}", 0, 0, category = "音訊")
        selectTrack(id)
        return true
    }
    @Synchronized fun addTrack(drums: Boolean = false): Boolean {
        val used = tracks.map { it.channel }.toSet()
        val channel = (if (drums) listOf(9) + (0..15).filter { it != 9 }
            else (0..15).filter { it != 9 })
            .firstOrNull { it !in used } ?: return false
        val id = nextId++
        tracks += Track(id, channel, if (drums) "鼓組 $id" else "樂器 $id",
            if (drums) 128 else 0, 0)
        val previous = inputTargetIds.toSet()
        selectedId = id
        inputTargetIds.clear()
        inputTargetIds += id
        releaseDepartedInputs(previous, inputTargetIds)
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
        val previous = inputTargetIds.toSet()
        selectedId = id
        inputTargetIds.clear()
        inputTargetIds += id
        releaseDepartedInputs(previous, inputTargetIds)
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
            releaseLiveTrack(it.id)
        }
    }

    @Synchronized fun removeTrack(id: Int) {
        val track = tracks.firstOrNull { it.id == id } ?: return
        if (id in recordingTargets || id in armedTargets) stopRecord(absoluteTick)
        releaseLiveTrack(track.id)
        tracks.remove(track)
        midiRoutes.removeAll { it.trackId == id }
        silenceInaudibleTracks()
        if (selectedId == id) selectedId = tracks.firstOrNull()?.id ?: 0
        inputTargetIds.remove(id)
        if (inputTargetIds.isEmpty() && selectedId != 0) inputTargetIds += selectedId
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
        if (track.isAudio) return
        releaseLiveTrack(track.id)
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
        if (t.isAudio || t.channel == 9 || category !in GmCategories.names) return
        t.category = category
        changed()
    }

    @Synchronized fun setInstrument(
        fontId: String, bank: Int, program: Int, presetName: String, category: String
    ) {
        val track = tracks.firstOrNull { it.id == selectedId } ?: return
        if (track.isAudio || fontId !in fontIndexes) return
        releaseLiveTrack(track.id)
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
        if (t.isAudio || !fontIndexes.containsKey(fontId)) return
        releaseLiveTrack(t.id)
        t.fontId = fontId
        recoverInstrumentName(t)
        NativeEngine.selectFontPreset(t.channel, fontIndexes[fontId] ?: 0, t.bank, t.program)
        changed()
    }
    @Synchronized fun replaceFont(oldId: String, replacementId: String = "gm") {
        tracks.filter { !it.isAudio && it.fontId == oldId }.forEach {
            releaseLiveTrack(it.id)
            it.fontId = replacementId
        }
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
            if (t.isAudio) continue
            NativeEngine.selectFontPreset(
                t.channel, fontIndexes[t.fontId] ?: 0,
                t.bank, t.program
            )
            NativeEngine.midi(NativeEngine.CONTROLLER, t.channel, 7, t.volume)
            // Expression is an independent persistent gain controller.
            // Keep it at full volume unless the user explicitly routes CC11.
            NativeEngine.midi(NativeEngine.CONTROLLER, t.channel, 11, 127)
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
        cancelAudio()
        NativeEngine.setAudioClock(0, 0)
        for (slot in 0 until 8) NativeEngine.setAudioClip(slot, ShortArray(0))
        save()
        scheduler.shutdownNow()
    }
}
