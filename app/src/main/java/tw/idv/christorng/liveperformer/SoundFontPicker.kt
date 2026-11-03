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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Both add and change instrument follow the same flow:
 * choose SoundFont -> family -> concrete preset; never add a default piano track.
 */
@Composable
fun SoundFontPicker(
    track: LoopTrackView?,
    fonts: List<SoundFontItem>,
    context: Context,
    onChoose: (String, Int, Int, String, String) -> Unit,
    onDelete: (() -> Unit)? = null,
    onClose: () -> Unit
) {
    val prefs = remember(context) {
        context.getSharedPreferences("instrument-families", Context.MODE_PRIVATE)
    }
    var sourceId by remember(track?.id) { mutableStateOf(track?.fontId ?: "gm") }
    var family by remember(track?.id) {
        mutableIntStateOf(GmCategories.names.indexOf(track?.category)
            .takeIf { it >= 0 } ?: GmCategories.family(track?.program ?: 0))
    }
    var drums by remember(track?.id) { mutableStateOf(track?.channel == 9) }
    val source = fonts.firstOrNull { it.id == sourceId }
    val importedPresets by produceState(
        initialValue = emptyList<InstrumentPreset>(),
        key1 = source?.id
    ) {
        value = if (source == null || source.isBundled) emptyList()
        else withContext(Dispatchers.IO) {
            runCatching { SoundFontPresets.read(source.file).presets }
                .getOrDefault(emptyList())
        }
    }
    val familyName = if (drums) "鼓組" else GmCategories.names[family]
    val presets = if (source?.isBundled == true) {
        if (drums) listOf(InstrumentPreset(128, 0, "Standard Drum Kit"))
        else GmCategories.instruments(family).map { program ->
            InstrumentPreset(0, program, GmPrograms.names[program])
        }
    } else {
        importedPresets.filter { preset ->
            val category = InstrumentNaming.category(source?.title.orEmpty(), preset.program, preset.name)
            if (drums) preset.bank == 128
            else preset.bank != 128 && category == familyName
        }
    }
    val lastProgram = prefs.getInt("last:${sourceId}:${familyName}",
        if (source?.isBundled == true) GmCategories.defaults[family] else (track?.program ?: 0))

    fun select(preset: InstrumentPreset) {
        val title = InstrumentNaming.bestName(preset.name, source?.title.orEmpty())
        prefs.edit().putInt("last:${sourceId}:${familyName}", preset.program).apply()
        onChoose(sourceId, preset.bank, preset.program, title, familyName)
        onClose()
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (track == null) "新增樂器軌" else "選擇樂器 · ${track.name}") },
        text = {
            Column {
                Text("1. 音色庫", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    fonts.forEach { font ->
                        FilterChip(
                            selected = sourceId == font.id,
                            onClick = { sourceId = font.id },
                            label = { Text(font.title, maxLines = 1) },
                            modifier = Modifier.padding(end = 5.dp)
                        )
                    }
                }
                Text("2. 樂器類別", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    if (track == null || track.channel == 9) {
                        FilterChip(selected = drums, onClick = { drums = true },
                            label = { Text("鼓組") }, modifier = Modifier.padding(end = 5.dp))
                    }
                    if (track?.channel != 9) {
                        GmCategories.names.forEachIndexed { index, name ->
                            FilterChip(selected = !drums && family == index,
                                onClick = { drums = false; family = index },
                                label = { Text(name) }, modifier = Modifier.padding(end = 5.dp))
                        }
                    }
                }
                Text("3. 選擇樂器", style = MaterialTheme.typography.labelMedium)
                presets.firstOrNull { it.program == lastProgram }?.let { favourite ->
                    TextButton(onClick = { select(favourite) }) {
                        Text("★ ${InstrumentNaming.bestName(favourite.name, source?.title.orEmpty())}",
                            maxLines = 1)
                    }
                }
                LazyColumn(Modifier.heightIn(max = 250.dp)) {
                    items(presets, key = { "${it.bank}:${it.program}" }) { preset ->
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { select(preset) }
                        ) {
                            val name = InstrumentNaming.bestName(preset.name, source?.title.orEmpty())
                            Text("${preset.program + 1}. $name", modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                if (presets.isEmpty()) Text("此音色庫沒有屬於「$familyName」的樂器，可切換類別或音色庫。")
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("取消") } },
        dismissButton = {
            if (track != null && onDelete != null) {
                TextButton(onClick = { onDelete(); onClose() }) { Text("刪除音軌") }
            }
        }
    )
}
