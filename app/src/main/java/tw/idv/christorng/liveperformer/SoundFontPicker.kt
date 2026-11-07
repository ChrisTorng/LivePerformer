package tw.idv.christorng.liveperformer

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** SoundFont -> available category -> real SoundFont presets. Track maintenance is in the track menu. */
@Composable
fun SoundFontPicker(
    track: LoopTrackView?,
    fonts: List<SoundFontItem>,
    context: Context,
    onChoose: (String, Int, Int, String, String) -> Unit,
    onClose: () -> Unit
) {
    val prefs = remember(context) {
        context.getSharedPreferences("instrument-families", Context.MODE_PRIVATE)
    }
    var sourceId by remember(track?.id) { mutableStateOf(track?.fontId ?: "gm") }
    var category by remember(track?.id) {
        mutableStateOf(track?.category ?: "鋼琴")
    }
    val source = fonts.firstOrNull { it.id == sourceId }
    val allPresets by produceState(
        initialValue = emptyList<InstrumentPreset>(),
        key1 = source?.id
    ) {
        value = if (source == null) emptyList() else withContext(Dispatchers.IO) {
            val parsed = runCatching { SoundFontPresets.read(source.file).presets }.getOrDefault(emptyList())
            if (parsed.isEmpty() && source.isBundled) {
                GmPrograms.names.mapIndexed { i, n -> InstrumentPreset(0, i, n) } +
                    InstrumentPreset(128, 0, "Standard Drum Kit")
            } else parsed
        }
    }
    fun categoryOf(preset: InstrumentPreset): String =
        if (preset.bank >= 128) "鼓組"
        else if (source?.isBundled == true)
            GmCategories.names[GmCategories.family(preset.program)]
        else InstrumentNaming.category(source?.title.orEmpty(), preset.program, preset.name)

    val availableCategories = allPresets.map(::categoryOf).distinct()
    val activeCategory = category.takeIf { it in availableCategories }
        ?: availableCategories.firstOrNull()
    val shownPresets = allPresets.filter { activeCategory != null && categoryOf(it) == activeCategory }
    val lastProgram = prefs.getInt("last:${sourceId}:${activeCategory}", -1)

    fun displayName(preset: InstrumentPreset): String =
        InstrumentNaming.presetDisplayName(
            preset.name, source?.title.orEmpty(), if (source?.isBundled == true) 0 else allPresets.size
        )
    fun select(preset: InstrumentPreset) {
        val title = displayName(preset)
        prefs.edit().putInt("last:${sourceId}:${activeCategory}", preset.program).apply()
        onChoose(sourceId, preset.bank, preset.program, title, activeCategory ?: "鋼琴")
        onClose()
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (track == null) "新增樂器" else "變更樂器") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("1. 音色庫", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    fonts.forEach { font ->
                        FilterChip(
                            selected = sourceId == font.id,
                            onClick = { sourceId = font.id },
                            label = { Text(font.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 188.dp)) },
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    }
                }
                Text("2. 可用樂器類別", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    availableCategories.forEach { name ->
                        FilterChip(
                            selected = activeCategory == name,
                            onClick = { category = name },
                            label = { Text(name) }, modifier = Modifier.padding(end = 6.dp)
                        )
                    }
                }
                Text("3. 樂器", style = MaterialTheme.typography.labelMedium)
                shownPresets.firstOrNull { it.program == lastProgram }?.let { favourite ->
                    TextButton(onClick = { select(favourite) }) {
                        Text("★ " + displayName(favourite),
                            maxLines = 1)
                    }
                }
                LazyColumn(Modifier.heightIn(max = 240.dp)) {
                    items(shownPresets, key = { "${it.bank}:${it.program}" }) { preset ->
                        TextButton(onClick = { select(preset) }, modifier = Modifier.fillMaxWidth()) {
                            Text("${preset.program + 1}. " +
                                displayName(preset),
                                modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                if (shownPresets.isEmpty()) Text(
                    if (allPresets.isEmpty()) "正在讀取音色庫，或找不到 SoundFont 樂器預設。"
                    else "此類別沒有適用於目前音軌的樂器。"
                )
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("取消") } }
    )
}
