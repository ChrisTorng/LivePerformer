package tw.idv.christorng.liveperformer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.zIndex
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
private fun TransportButton(symbol: String, description: String, recording: Boolean = false, onClick: () -> Unit) {
    val background = if (recording) Color(0xFFAD3030) else MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier.size(width = 59.dp, height = 50.dp)
            .background(background, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(symbol, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MetronomeToggle(enabled: Boolean, onClick: () -> Unit) {
    val active = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.size(43.dp).clickable(onClick = onClick)
        .background(if (enabled) active.copy(alpha = 0.2f)
            else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(26.dp)) {
            val c = if (enabled) active else muted
            val w = size.width
            val h = size.height
            val frame = Path().apply {
                moveTo(w * .35f, h * .30f); lineTo(w * .65f, h * .30f)
                lineTo(w * .83f, h * .88f); lineTo(w * .17f, h * .88f); close()
            }
            drawPath(frame, color = c, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            drawLine(c, Offset(w * .54f, h * .70f), Offset(w * .75f, h * .10f),
                strokeWidth = 2.dp.toPx())
            drawCircle(c, radius = 2.dp.toPx(), center = Offset(w * .75f, h * .10f))
        }
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
    onDelete: (Int) -> Unit,
    onClear: (Int) -> Unit,
    onRename: (Int) -> Unit
) {
    val landscape = LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE
    var showVolume by remember { mutableStateOf(false) }
    val labelWidth = if (landscape) 172.dp else 174.dp
    val itemHeight = if (landscape) 59.dp else 64.dp
    val dragThreshold = with(LocalDensity.current) { (itemHeight + 3.dp).toPx() }
    val keyboardHeight = when (keyboardMode) {
        KeyboardMode.HIDDEN -> 0.dp
        KeyboardMode.MONITOR -> if (landscape) 32.dp else 38.dp
        KeyboardMode.PLAY -> if (landscape) 91.dp else 148.dp
    }
    val anySolo = state.tracks.any { it.solo }
    Column(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 2.dp)) {
        // Project/system configuration are deliberately separate from stage controls.
        Row(Modifier.fillMaxWidth().height(39.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Image(painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = "LivePerformer", modifier = Modifier.size(31.dp))
            Text("LivePerformer", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onProjectSettings) { Text("專案") }
            TextButton(onClick = onSystemSettings) { Text("系統") }
        }

        Row(Modifier.fillMaxWidth().height(58.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            TransportButton(if (state.playing) "■" else "▶",
                if (state.playing) "停止" else "播放") { looper.playStop() }
            TransportButton(if (state.recording || state.armed) "■" else "●", "錄製",
                recording = true) { looper.recordPause() }
            if (state.recording || state.armed)
                Text(if (state.armed) "待錄" else "REC", color = Color(0xFFF87171), fontSize = 11.sp)
            MetronomeToggle(state.metronome) { looper.setMetronome(!state.metronome) }
            Text("${state.bpm} BPM", fontSize = 12.sp, maxLines = 1)
            Spacer(Modifier.weight(1f))
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

        Box(Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                items(state.tracks, key = { it.id }) { track ->
                    val isSelected = state.selectedId == track.id
                    val audible = !track.muted && (!anySolo || track.solo)
                    var dragPixels by remember(track.id) { mutableFloatStateOf(0f) }
                    var isDragging by remember(track.id) { mutableStateOf(false) }
                    var showTrackMenu by remember(track.id) { mutableStateOf(false) }
                    val floatingY by animateFloatAsState(
                        targetValue = if (isDragging) dragPixels else 0f,
                        animationSpec = tween(110), label = "track-float"
                    )
                    Row(Modifier.fillMaxWidth().height(itemHeight)
                        .zIndex(if (isDragging) 10f else 0f)
                        .graphicsLayer {
                            translationY = floatingY
                            scaleX = if (isDragging) 1.02f else 1f
                            scaleY = if (isDragging) 1.04f else 1f
                            shadowElevation = if (isDragging) 14.dp.toPx() else 0f
                        }) {
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
                                            onDragStart = {
                                                dragPixels = 0f
                                                isDragging = true
                                            },
                                            onDragEnd = { isDragging = false; dragPixels = 0f },
                                            onDragCancel = { isDragging = false; dragPixels = 0f },
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
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                Box(Modifier.fillMaxSize().clickable {
                                    if (isSelected) showTrackMenu = true
                                    else looper.selectTrack(track.id)
                                }, contentAlignment = Alignment.CenterStart) {
                                    Text(track.name.ifBlank { track.category },
                                        modifier = Modifier.padding(horizontal = 3.dp),
                                        fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                DropdownMenu(expanded = showTrackMenu,
                                    onDismissRequest = { showTrackMenu = false }) {
                                    DropdownMenuItem(text = { Text("變更樂器") }, onClick = {
                                        showTrackMenu = false
                                        onInstrument()
                                    })
                                    DropdownMenuItem(text = { Text("MIDI 控制設定") }, onClick = {
                                        showTrackMenu = false
                                        looper.selectTrack(track.id)
                                        onProjectSettings()
                                    })
                                    DropdownMenuItem(text = { Text("重新命名") }, onClick = {
                                        showTrackMenu = false
                                        onRename(track.id)
                                    })
                                    DropdownMenuItem(text = { Text("清除錄製") }, onClick = {
                                        showTrackMenu = false
                                        onClear(track.id)
                                    })
                                    DropdownMenuItem(text = { Text("刪除音軌") }, onClick = {
                                        showTrackMenu = false
                                        onDelete(track.id)
                                    })
                                }
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
                            Text("+", fontWeight = FontWeight.Bold, fontSize = 26.sp)
                        }
                        Spacer(Modifier.weight(1f))
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
