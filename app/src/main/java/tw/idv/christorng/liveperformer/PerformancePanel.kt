package tw.idv.christorng.liveperformer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.ceil
import kotlin.math.floor

enum class KeyboardMode(val title: String) {
    PLAY("鍵盤"), MONITOR("監看"), HIDDEN("隱藏");
    fun next(): KeyboardMode = entries[(ordinal + 1) % entries.size]
}

private val laneColors = listOf(
    Color(0xFFF87171), Color(0xFFFBBF24), Color(0xFF4ADE80),
    Color(0xFF60A5FA), Color(0xFFC084FC), Color(0xFF22D3EE),
    Color(0xFFFB7185), Color(0xFF34D399)
)
private fun laneColor(channel: Int): Color =
    laneColors[if (channel == 9) 0 else (channel % 7) + 1]

@Composable
private fun SmallAction(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.width(if (label.length > 3) 58.dp else 38.dp)
            .height(34.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary, maxLines = 1)
    }
}

@Composable
private fun TransportButton(label: String, action: String, recording: Boolean = false, onClick: () -> Unit) {
    val background = if (recording) Color(0xFFAA3434) else MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier.width(78.dp).height(56.dp)
            .background(background, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, color = Color.White, fontSize = 24.sp, lineHeight = 25.sp,
            fontWeight = FontWeight.Bold)
        Text(action, color = Color.White, fontSize = 11.sp)
    }
}

@Composable
private fun TrackToggle(label: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier.width(38.dp).height(43.dp)
            .background(if (selected) color else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = 14.sp,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface)
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
    onProjectSettings: () -> Unit,
    onSystemSettings: () -> Unit,
    onInstrument: () -> Unit,
    onAddTrack: () -> Unit,
    onDelete: (Int) -> Unit
) {
    val landscape = LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE
    var showVolume by remember { mutableStateOf(false) }
    val labelWidth = if (landscape) 218.dp else 205.dp
    val itemHeight = if (landscape) 59.dp else 64.dp
    val dragThreshold = with(LocalDensity.current) { (itemHeight + 3.dp).toPx() }
    val keyboardHeight = when (keyboardMode) {
        KeyboardMode.HIDDEN -> 0.dp
        KeyboardMode.MONITOR -> if (landscape) 32.dp else 38.dp
        KeyboardMode.PLAY -> if (landscape) 91.dp else 148.dp
    }
    val groups = state.tracks.groupBy { it.category }
    val anySolo = state.tracks.any { it.solo }
    Column(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 2.dp)) {
        // Project/system configuration are deliberately separate from stage controls.
        Row(Modifier.fillMaxWidth().height(39.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onProjectSettings) { Text("專案設定") }
            TextButton(onClick = onSystemSettings) { Text("系統設定") }
            Spacer(Modifier.weight(1f))
            Text("LivePerformer", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Image(painter = painterResource(R.mipmap.ic_launcher),
                contentDescription = "LivePerformer",
                modifier = Modifier.size(32.dp))
        }

        Row(Modifier.fillMaxWidth().height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TransportButton(if (state.playing) "Ⅱ" else "▶",
                if (state.playing) "暫停" else "播放") { looper.playPause() }
            TransportButton(if (state.recording || state.armed) "■" else "●",
                when { state.recording -> "停止錄製"
                    state.armed -> "取消待錄"
                    else -> "錄製" },
                recording = true) { looper.recordPause() }
            // Clear is intentionally distant from playback and record.
            Spacer(Modifier.width(6.dp))
            SmallAction("清除", { looper.clearSelected() })
            SmallAction(if (state.metronome) "♩開" else "♩關",
                { looper.setMetronome(!state.metronome) })
            Spacer(Modifier.weight(1f))
            Text("${state.beatsPerBar}/${state.beatUnit} · ${state.bars} 小節 · ${state.bpm} BPM",
                fontSize = 12.sp, maxLines = 1)
            Box {
                SmallAction("音量", { showVolume = true })
                DropdownMenu(expanded = showVolume, onDismissRequest = { showVolume = false }) {
                    Column(Modifier.width(210.dp).padding(12.dp)) {
                        Text("音量")
                        Slider(value = volume, onValueChange = onVolume,
                            valueRange = 0.1f..1.5f)
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().height(19.dp),
            verticalAlignment = Alignment.CenterVertically) {
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
            Row(Modifier.weight(1f).fillMaxHeight()) {
                repeat(state.bars) { bar ->
                    Box(Modifier.weight(1f)) {
                        Text("${bar + 1}", fontSize = 11.sp,
                            modifier = Modifier.align(Alignment.CenterStart))
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                items(state.tracks, key = { it.id }) { track ->
                    val isSelected = state.selectedId == track.id
                    val categoryTracks = groups[track.category].orEmpty()
                    val number = categoryTracks.indexOfFirst { it.id == track.id } + 1
                    val groupName = if (categoryTracks.size > 1)
                        "${track.category} $number" else track.category
                    val audible = !track.muted && (!anySolo || track.solo)
                    var dragPixels by remember(track.id) { mutableFloatStateOf(0f) }

                    Row(Modifier.fillMaxWidth().height(itemHeight)) {
                        Row(
                            Modifier.width(labelWidth).fillMaxHeight()
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.width(25.dp).fillMaxHeight()
                                    .pointerInput(track.id, dragThreshold) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { dragPixels = 0f },
                                            onDragEnd = { dragPixels = 0f },
                                            onDragCancel = { dragPixels = 0f },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                dragPixels += amount.y
                                                if (dragPixels >= dragThreshold) {
                                                    looper.moveTrack(track.id, 1)
                                                    dragPixels -= dragThreshold
                                                } else if (dragPixels <= -dragThreshold) {
                                                    looper.moveTrack(track.id, -1)
                                                    dragPixels += dragThreshold
                                                }
                                            }
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) { Text("⠿", fontSize = 18.sp) }
                            Column(Modifier.weight(1f).fillMaxHeight()
                                .clickable {
                                    if (isSelected) onInstrument()
                                    else looper.selectTrack(track.id)
                                },
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(groupName, fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                Text(track.name, fontSize = 10.sp, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                            }
                            TrackToggle("M", track.muted, Color(0xFFAA3434)) {
                                looper.muteTrack(track.id)
                            }
                            Spacer(Modifier.width(3.dp))
                            TrackToggle("S", track.solo, Color(0xFF966600)) {
                                looper.toggleSolo(track.id)
                            }
                            Spacer(Modifier.width(3.dp))
                        }
                        PianoRoll(track, state.bars, state.beatsPerBar, audible,
                            Modifier.weight(1f).fillMaxHeight()
                                .clickable { looper.selectTrack(track.id) })
                    }
                }
                item(key = "add-track") {
                    Row(Modifier.fillMaxWidth().height(46.dp)
                        .clickable(onClick = onAddTrack),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(labelWidth).fillMaxHeight()
                            .background(MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center) {
                            Text("+ 新增樂器軌", fontWeight = FontWeight.Bold)
                        }
                        Box(Modifier.weight(1f).fillMaxHeight(),
                            contentAlignment = Alignment.CenterStart) {
                            Text("選擇類別及樂器", fontSize = 12.sp,
                                modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }
            // One playhead is aligned to every piano-roll row, even if the list scrolls.
            Canvas(Modifier.fillMaxSize()) {
                val left = labelWidth.toPx()
                val width = (size.width - left).coerceAtLeast(0f)
                val x = left + width * state.progress.coerceIn(0f, 1f)
                drawLine(Color.White.copy(alpha = 0.90f),
                    Offset(x, 0f), Offset(x, size.height),
                    strokeWidth = 2.dp.toPx())
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
private fun PianoRoll(
    track: LoopTrackView, bars: Int, beatsPerBar: Int,
    audible: Boolean, modifier: Modifier
) {
    Canvas(modifier.background(Color(0xFF161F30),
        RoundedCornerShape(topEnd = 7.dp, bottomEnd = 7.dp))) {
        val w = size.width
        val h = size.height
        repeat(bars * beatsPerBar) { step ->
            if (step == 0) return@repeat
            val x = w * step / (bars * beatsPerBar)
            drawLine(Color.White.copy(alpha = if (step % beatsPerBar == 0) .42f else .10f),
                Offset(x, 0f), Offset(x, h),
                strokeWidth = if (step % beatsPerBar == 0) 1.7.dp.toPx() else .6.dp.toPx())
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
                .coerceAtMost((w - x).coerceAtLeast(0f))
            val y = h - (note.pitch - lower + 1) * cell
            val alpha = if (!audible) .20f
                else (.38f + .62f * note.velocity.coerceIn(0, 127) / 127f)
            drawRoundRect(color.copy(alpha = alpha),
                topLeft = Offset(x, y),
                size = Size(width, cell.coerceAtLeast(2.dp.toPx())),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
        }
    }
}
