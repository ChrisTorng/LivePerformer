package tw.idv.christorng.liveperformer

import android.content.Context
import org.json.JSONObject

/**
 * Physical pads have stable musical roles. All recorded drum events use the GM note
 * for that role, regardless of the current SoundFont. Only the final synth output
 * is translated, so changing kits does not rewrite or corrupt a recorded loop.
 */
object DrumPads {
    val keys = listOf(
        "bassDrum", "snare", "closedHiHat", "openHiHat",
        "lowTom", "highTom", "ride", "crash"
    )
    val gmNotes = listOf(36, 38, 42, 46, 45, 50, 51, 49)
    const val FIRST_MPK_NOTE = 36
    const val LAST_MPK_NOTE = 43

    /** Input note or CC number identifies a physical pad, not a GM pitch. */
    fun canonicalForPad(number: Int, first: Int = FIRST_MPK_NOTE): Int? =
        gmNotes.getOrNull(number - first)

    fun roleForCanonical(note: Int): Int = gmNotes.indexOf(note)
}

data class DrumMap(val name: String, val notes: List<Int>) {
    init {
        require(notes.size == DrumPads.keys.size && notes.all { it in 0..127 }) {
            "鼓組對應須完整提供 8 個 MIDI Note（0–127）"
        }
        require(notes[2] != notes[3]) { "Open / Closed Hi-hat 不能使用同一個 Note" }
    }

    fun outputNote(canonical: Int): Int {
        val role = DrumPads.roleForCanonical(canonical)
        return if (role < 0) canonical else notes[role]
    }

    companion object {
        val GM = DrumMap("GM Standard", DrumPads.gmNotes)

        /**
         * Example:
         * {"format":"liveperformer.drum-map.v1","name":"My Kit",
         *  "notes":{"bassDrum":36,"snare":38,"closedHiHat":42,
         *           "openHiHat":46,"lowTom":45,"highTom":50,"ride":51,"crash":49}}
         */
        fun fromJson(json: String): DrumMap {
            require(json.length <= 16384) { "鼓組對應檔過大" }
            val obj = JSONObject(json)
            require(obj.getString("format") == "liveperformer.drum-map.v1") {
                "不支援的鼓組對應格式"
            }
            val name = obj.getString("name").trim()
            require(name.isNotEmpty() && name.length <= 100) { "鼓組名稱無效" }
            val values = obj.getJSONObject("notes")
            val notes = DrumPads.keys.map { key ->
                val value = values.get(key)
                require(value is Int && value in 0..127) { "$key 必須是 0–127 的 MIDI Note" }
                value
            }
            return DrumMap(name, notes)
        }
    }
}

/** Global per-SoundFont overrides, deliberately separate from per-project MIDI routes. */
class DrumMapStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "drum-map-overrides", Context.MODE_PRIVATE
    )
    private val cache = mutableMapOf<String, DrumMap>()

    @Synchronized fun forFont(fontId: String): DrumMap =
        cache.getOrPut(fontId) {
            val json = prefs.getString("map:$fontId", null) ?: return@getOrPut DrumMap.GM
            runCatching { DrumMap.fromJson(json) }.getOrDefault(DrumMap.GM)
        }

    @Synchronized fun install(fontId: String, json: String): DrumMap {
        require(fontId.isNotBlank() && fontId.length <= 256)
        val parsed = DrumMap.fromJson(json)
        require(prefs.edit().putString("map:$fontId", json).commit()) {
            "無法儲存鼓組對應"
        }
        cache[fontId] = parsed
        return parsed
    }

    @Synchronized fun reset(fontId: String) {
        require(prefs.edit().remove("map:$fontId").commit()) {
            "無法重設鼓組對應"
        }
        cache.remove(fontId)
    }
}
