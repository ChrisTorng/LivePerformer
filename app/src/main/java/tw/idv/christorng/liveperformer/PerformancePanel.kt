package tw.idv.christorng.liveperformer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.ceil
import kotlin.math.floor

enum class KeyboardMode(val title: String) {
    PLAY("鍵盤"), MONITOR("監看"), HIDDEN("隱藏");
    fun next(): KeyboardMode = entries[(ordinal + 1) % entries.size]
}

private val colors = listOf(
    Color(0xFFF87171), Color(0xFFFBBF24), Color(0xFF4ADE80),
    Color(0xFF60A5FA), Color(0xFFC084FC), Color(0xFF22D3EE),
    Color(0xFFFB7185), Color(0xFF34D399)
)
private fun laneColor(channel: Int): Color = colors[if (channel == 9) 0 else (channel % 7) + 1]

@Composable
private fun SmallAction(label: String, onClick: () -> Unit, emphasized: Boolean = false) {
    if (emphasized) {
        Button(onClick = onClick, contentPadding = PaddingValues(horizontal = 11.dp),
            modifier = Modifier.height(35.dp)) { Text(label, fontSize = 14.sp) }
    } else {
        TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 5.dp),
            modifier = Modifier.height(34.dp)) { Text(label, fontSize = 13.sp, maxLines = 1) }
    }
}

@Composable
fun PerformancePanel(
    state: LoopView,
    looper: LoopController,
    keyboard: PianoKeyboardView,
    keyboardMode: KeyboardMode,
    onKeyboardMode: () -> Unit,
    baseNote: Int,
    onBaseNote: (Int) -> Unit,
    velocity: Int,
    volume: Float,
    onVolume: (Float) -> Unit,
    onSettings: () -> Unit,
    onInstrument: () -> Unit,
    onDemo: () -> Unit,
    onDelete: (Int) -> Unit
) {
    val landscape = LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE
    var showBars by remember { mutableStateOf(false) }
    var showVolume by remember { mutableStateOf(false) }
    var trackMenu by remember { mutableStateOf<Int?>(null) }
    val labelWidth = if (landscape) 96.dp else 83.dp
    val keyboardHeight = when (keyboardMode) {
        KeyboardMode.HIDDEN -> 0.dp
        KeyboardMode.MONITOR -> if (landscape) 32.dp else 38.dp
        KeyboardMode.PLAY -> if (landscape) 91.dp else 148.dp
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 5.dp, vertical = 2.dp)) {
        // One compact transport row. No independent transport progress bar.
        Row(
            Modifier.fillMaxWidth().height(40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            SmallAction(if (state.playing) "Ⅱ" else "▶",
                { looper.playPause() }, emphasized = true)
            SmallAction(if (state.recording || state.armed) "■" else "●",
                { looper.recordPause() }, emphasized = true)
            SmallAction("✕", { looper.clearSelected() })
            SmallAction("−", { looper.setBpm(state.bpm - 1) })
            Text("${state.bpm}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            SmallAction("+", { looper.setBpm(state.bpm + 1) })
            if (landscape) Text("BPM", fontSize = 10.sp)
            Box {
                SmallAction("${state.bars}節⌄", { showBars = true })
                DropdownMenu(expanded = showBars, onDismissRequest = { showBars = false }) {
                    listOf(1, 2, 4, 8).forEach { count ->
                        DropdownMenuItem(text = { Text("$count 小節") }, onClick = {
                            looper.setBars(count)
                            showBars = false
                        })
                    }
                }
            }
            if (landscape) {
                SmallAction(if (state.metronome) "♩" else "♩̸",
                    { looper.setMetronome(!state.metronome) })
            }
            Spacer(Modifier.weight(1f))
            if (landscape) {
                SmallAction("+軌", { looper.addTrack() })
                SmallAction("示範", onDemo)
            }
            Box {
                SmallAction("音量", { showVolume = true })
                DropdownMenu(expanded = showVolume, onDismissRequest = { showVolume = false }) {
                    Column(Modifier.width(200.dp).padding(12.dp)) {
                        Text("音量")
                        Slider(value = volume, onValueChange = onVolume, valueRange = 0.1f..1.5f)
                    }
                }
            }
            SmallAction("⚙", onSettings)
        }

        if (!landscape) {
            Row(
                Modifier.fillMaxWidth().height(29.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SmallAction(if (state.metronome) "♩" else "♩̸",
                    { looper.setMetronome(!state.metronome) })
                Spacer(Modifier.weight(1f))
                SmallAction("+軌", { looper.addTrack() })
                SmallAction("示範", onDemo)
            }
        }

        // Thin bar numbers align exactly with the note area, not another progress strip.
        Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    state.recording -> "● REC"
                    state.armed -> "● 待錄"
                    else -> "音軌"
                },
                color = if (state.recording || state.armed) Color(0xFFFA6767)
                    else MaterialTheme.colorScheme.onSurface,
                fontSize = 11.sp, modifier = Modifier.width(labelWidth)
            )
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.fillMaxSize()) {
                    repeat(state.bars) { bar ->
                        Box(Modifier.weight(1f)) {
                            Text("${bar+1}", fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterStart))
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                items(state.tracks, key = { it.id }) { track ->
                    val isSelected = state.selectedId == track.id
                    val groupName = if (track.channel == 9) "鼓組"
                        else GmCategories.names[GmCategories.family(track.program)]
                    Row(Modifier.fillMaxWidth().height(if (landscape) 57.dp else 62.dp)) {
                        Box(
                            Modifier.width(labelWidth).fillMaxHeight()
                                .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
                                .clickable {
                                    if (isSelected) onInstrument() else looper.selectTrack(track.id)
                                },
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Row(Modifier.fillMaxWidth().padding(start = 5.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.width(3.dp).height(25.dp)
                                    .background(laneColor(track.channel)))
                                Text(groupName, fontSize = if (landscape) 13.sp else 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f).padding(start = 3.dp))
                                Box {
                                    TextButton(onClick = { trackMenu = track.id },
                                        contentPadding = PaddingValues(0.dp),
                                        modifier = Modifier.width(28.dp)) { Text("⋮") }
                                    DropdownMenu(
                                        expanded = trackMenu == track.id,
                                        onDismissRequest = { trackMenu = null }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(if (track.muted) "取消靜音" else "靜音") },
                                            onClick = {
                                                looper.muteTrack(track.id)
                                                trackMenu = null
                                            })
                                        DropdownMenuItem(
                                            text = { Text("刪除音軌") },
                                            enabled = state.tracks.size > 1,
                                            onClick = { onDelete(track.id); trackMenu = null })
                                    }
                                }
                            }
                        }
                        PianoRoll(track, state.bars, Modifier.weight(1f).fillMaxHeight()
                            .clickable { looper.selectTrack(track.id) })
                    }
                }
            }
            // One shared vertical playhead crossing all tracks, including while list scrolls.
            Canvas(Modifier.fillMaxSize()) {
                val left = labelWidth.toPx()
                val width = (size.width - left).coerceAtLeast(0f)
                val x = left + width * state.progress.coerceIn(0f, 1f)
                drawLine(Color.White.copy(alpha = 0.90f),
                    Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
            }
        }

        Row(Modifier.fillMaxWidth().height(32.dp),
            verticalAlignment = Alignment.CenterVertically) {
            SmallAction(
                when (keyboardMode) {
                    KeyboardMode.PLAY -> "▤ 鍵盤"
                    KeyboardMode.MONITOR -> "▥ 監看"
                    KeyboardMode.HIDDEN -> "▧ 隱藏"
                }, onKeyboardMode)
            Spacer(Modifier.weight(1f))
            if (keyboardMode != KeyboardMode.HIDDEN) {
                SmallAction("−", { onBaseNote((baseNote - 12).coerceAtLeast(0)) })
                Text("C${baseNote / 12 - 1}", fontSize = 12.sp)
                SmallAction("+", { onBaseNote((baseNote + 12).coerceAtMost(96)) })
            }
        }
        if (keyboardMode != KeyboardMode.HIDDEN) {
            AndroidView(
                factory = { keyboard },
                update = {
                    it.baseNote = baseNote
                    it.velocity = velocity
                    it.readOnly = keyboardMode == KeyboardMode.MONITOR
                    it.showExternalNotes = state.keyboardNotes
                },
                modifier = Modifier.fillMaxWidth().height(keyboardHeight)
            )
        }
    }
}

@Composable
private fun PianoRoll(track: LoopTrackView, bars: Int, modifier: Modifier) {
    Canvas(modifier.background(Color(0xFF161F30),
        RoundedCornerShape(topEnd = 7.dp, bottomEnd = 7.dp))) {
        val w = size.width
        val h = size.height
        repeat(bars * 4) { step ->
            if (step == 0) return@repeat
            val x = w * step / (bars * 4)
            drawLine(Color.White.copy(alpha = if (step % 4 == 0) .42f else .10f),
                Offset(x, 0f), Offset(x, h),
                strokeWidth = if (step % 4 == 0) 1.7.dp.toPx() else .6.dp.toPx())
        }
        val notes = track.noteRects
        if (notes.isEmpty()) return@Canvas
        val lower = (floor((notes.minOf { it.pitch }.toDouble() - 2) / 12.0) * 12).toInt()
        val upper = (ceil((notes.maxOf { it.pitch }.toDouble() + 3) / 12.0) * 12).toInt()
            .coerceAtLeast(lower + 12)
        val span = upper - lower + 1
        val cell = h / span
        val color = laneColor(track.channel)
        notes.forEach { note ->
            val x = w * note.start
            val width = (w * note.length).coerceAtLeast(2.dp.toPx())
                .coerceAtMost(w - x)
            val y = h - (note.pitch - lower + 1) * cell
            val alpha = if (track.muted) .20f
                else (.38f + .62f * note.velocity.coerceIn(0,127) / 127f)
            drawRoundRect(color.copy(alpha = alpha),
                topLeft = Offset(x, y),
                size = Size(width, cell.coerceAtLeast(2.dp.toPx())),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
        }
    }
}
