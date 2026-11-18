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

data class DrumMap(val name: String, val notes: List<Int>,
                   val bank: Int? = null, val program: Int? = null) {
    init {
        require((bank == null && program == null) ||
            (bank != null && program != null && bank in 0..16383 && program in 0..127)) { "bank / program 必須同時指定" }
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
            val bank = if (obj.has("bank")) obj.getInt("bank") else null
            val program = if (obj.has("program")) obj.getInt("program") else null
            val values = obj.getJSONObject("notes")
            val notes = DrumPads.keys.map { key ->
                val value = values.get(key)
                require(value is Int && value in 0..127) { "$key 必須是 0–127 的 MIDI Note" }
                value
            }
            return DrumMap(name, notes, bank, program)
        }
    }
}

/** Global per-SoundFont overrides, deliberately separate from per-project MIDI routes. */
class DrumMapStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "drum-map-overrides", Context.MODE_PRIVATE
    )
    private val cache = mutableMapOf<String, DrumMap>()

    private fun fontKey(fontId: String) = "map:${fontId}"
    private fun presetKey(fontId: String, bank: Int, program: Int) =
        "map:${fontId}:${bank}:${program}"

    private fun find(key: String): DrumMap? {
        if (cache.containsKey(key)) return cache[key]
        val json = prefs.getString(key, null) ?: return null
        val map = runCatching { DrumMap.fromJson(json) }.getOrNull() ?: return null
        cache[key] = map
        return map
    }

    @Synchronized fun forFont(fontId: String): DrumMap =
        find(fontKey(fontId)) ?: DrumMap.GM

    /** A SoundFont can contain several drum presets with different key layouts. */
    @Synchronized fun forPreset(fontId: String, bank: Int, program: Int): DrumMap =
        find(presetKey(fontId, bank, program)) ?: forFont(fontId)

    @Synchronized fun description(fontId: String): String {
        val default = forFont(fontId).name
        val count = prefs.all.keys.count { it.startsWith(fontKey(fontId) + ":") }
        return if (count == 0) default else "${default}（另有 ${count} 個預設對應）"
    }

    @Synchronized fun install(fontId: String, json: String): DrumMap {
        require(fontId.isNotBlank() && fontId.length <= 256)
        val parsed = DrumMap.fromJson(json)
        val key = if (parsed.bank != null && parsed.program != null)
            presetKey(fontId, parsed.bank, parsed.program) else fontKey(fontId)
        require(prefs.edit().putString(key, json).commit()) {
            "無法儲存鼓組對應"
        }
        cache[key] = parsed
        return parsed
    }

    /** Reset all overrides for this bank, including special per-preset maps. */
    @Synchronized fun reset(fontId: String) {
        val base = fontKey(fontId)
        val keys = prefs.all.keys.filter { it == base || it.startsWith(base + ":") }
        val edit = prefs.edit()
        keys.forEach { edit.remove(it) }
        require(edit.commit()) { "無法重設鼓組對應" }
        keys.forEach(cache::remove)
    }
}
