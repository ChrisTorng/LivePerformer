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

/** SoundFonts remain loaded simultaneously. Every track stores its own font/preset. */
@Composable
fun SoundFontPicker(
    track: LoopTrackView,
    fonts: List<SoundFontItem>,
    context: Context,
    onChoose: (String, Int, Int) -> Unit,
    onClose: () -> Unit
) {
    val prefs = remember(context) {
        context.getSharedPreferences("instrument-families", Context.MODE_PRIVATE)
    }
    var sourceId by remember(track.id) { mutableStateOf(track.fontId) }
    var family by remember(track.id) {
        mutableIntStateOf(GmCategories.family(track.program))
    }
    val source = fonts.firstOrNull { it.id == sourceId }
    val importedPresets by produceState(
        initialValue = emptyList<InstrumentPreset>(),
        key1 = source?.id
    ) {
        value = if (source == null || source.isBundled) emptyList()
        else withContext(Dispatchers.IO) {
            runCatching { SoundFontPresets.read(source.file).presets }.getOrDefault(emptyList())
        }
    }
    val presets = if (source?.isBundled == true) {
        if (track.channel == 9) {
            listOf(InstrumentPreset(128, 0, "Standard Drum Kit"))
        } else {
            GmCategories.instruments(family).map { program ->
                InstrumentPreset(0, program, GmPrograms.names[program])
            }
        }
    } else importedPresets.filter {
        if (track.channel == 9) it.bank == 128 else true
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("樂器 · ${track.name}") },
        text = {
            Column {
                Text("音色庫", style = MaterialTheme.typography.labelMedium)
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
                if (source?.isBundled == true && track.channel != 9) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        GmCategories.names.forEachIndexed { index, name ->
                            FilterChip(selected = family == index,
                                onClick = { family = index }, label = { Text(name) },
                                modifier = Modifier.padding(end = 5.dp))
                        }
                    }
                }
                val lastProgram = prefs.getInt("last:${sourceId}:$family",
                    if (source?.isBundled == true) GmCategories.defaults[family] else track.program)
                val favourite = presets.firstOrNull { it.program == lastProgram }
                if (favourite != null) {
                    TextButton(onClick = {
                        onChoose(sourceId, favourite.bank, favourite.program)
                        onClose()
                    }) { Text("★ ${favourite.name}", maxLines = 1) }
                }
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(presets, key = { "${it.bank}:${it.program}" }) { preset ->
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                prefs.edit().putInt("last:${sourceId}:$family", preset.program).apply()
                                onChoose(sourceId, preset.bank, preset.program)
                                onClose()
                            }) {
                            Text(preset.displayName, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                if (presets.isEmpty()) Text("此音色庫沒有適用的音色")
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("關閉") } }
    )
}
