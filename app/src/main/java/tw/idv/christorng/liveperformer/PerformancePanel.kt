package tw.idv.christorng.liveperformer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import androidx.compose.ui.geometry.CornerRadius

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
        modifier = Modifier.size(width = 53.dp, height = 50.dp)
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
    Box(Modifier.size(40.dp).clickable(onClick = onClick)
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
        modifier = Modifier.width(34.dp).height(42.dp)
            .background(if (selected) color else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = 14.sp,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface)
    }
}

/** Two visual channels: constant slider fill is the configured gain; the dot is activity/peak. */
@Composable
private fun VerticalLevelMeter(
    setting: Float, signal: Float, muted: Boolean, modifier: Modifier = Modifier
) {
    val base = if (muted) Color(0xFF737981) else Color(0xFF2ABF86)
    val dot = if (muted) Color(0xFFB1B5BA) else
        if (signal >= .98f) Color(0xFFFF5C58) else Color(0xFFF7D764)
    Canvas(modifier.width(5.dp).padding(vertical = 3.dp)) {
        val w = size.width
        val h = size.height
        drawRoundRect(Color(0xFF24303A), cornerRadius =
            androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
        val level = setting.coerceIn(0f, 1f)
        drawRoundRect(base.copy(alpha = if (muted) .48f else .7f),
            topLeft = Offset(0f, h * (1f - level)),
            size = Size(w, h * level),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
        val y = h * (1f - signal.coerceIn(0f, 1f))
        if (signal > .005f) drawCircle(dot, radius = w * .48f,
            center = Offset(w / 2f, y.coerceIn(2f, h - 2f)))
    }
}

/**
 * Master gain is a true vertical fader: top=150%, bottom=0%.
 * The narrow adjacent meter continues to represent the real PCM output peak.
 */
@Composable
fun VerticalGainSlider(value: Float, onValueChange: (Float) -> Unit, maxValue: Float = 1.5f) {
    val primary = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.surfaceVariant
    val onChange by rememberUpdatedState(onValueChange)
    val edge = with(LocalDensity.current) { 12.dp.toPx() }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (maxValue == 1.5f) "${(value * 100).toInt()}%" 
            else "${value.roundToInt()} / ${maxValue.toInt()}", fontSize = 13.sp)
        Canvas(
            Modifier.width(42.dp).height(172.dp)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val travel = (size.height - edge * 2f).coerceAtLeast(1f)
                        onChange(((size.height - edge - offset.y) / travel * maxValue).coerceIn(0f, maxValue))
                    }
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { change, _ ->
                            change.consume()
                            val travel = (size.height - edge * 2f).coerceAtLeast(1f)
                            onChange(((size.height - edge - change.position.y) / travel * maxValue)
                                .coerceIn(0f, maxValue))
                        }
                    )
                }
        ) {
            val top = edge
            val bottom = size.height - edge
            val travel = bottom - top
            val middle = size.width / 2f
            val ratio = (value / maxValue).coerceIn(0f, 1f)
            drawRoundRect(background, Offset(middle - 5.dp.toPx(), top),
                Size(10.dp.toPx(), travel), CornerRadius(5.dp.toPx()))
            drawRoundRect(primary, Offset(middle - 5.dp.toPx(), bottom - travel * ratio),
                Size(10.dp.toPx(), travel * ratio), CornerRadius(5.dp.toPx()))
            drawCircle(primary, radius = 8.dp.toPx(),
                center = Offset(middle, bottom - travel * ratio))
        }
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
    masterPeak: Float,
    onVolume: (Float) -> Unit,
    onProjectSettings: () -> Unit,
    onMidiSettings: () -> Unit,
    onSystemSettings: () -> Unit,
    onInstrument: () -> Unit,
    onAddTrack: () -> Unit,
    onDelete: (Int) -> Unit,
    onClear: (Int) -> Unit,
    onRename: (Int) -> Unit,
    midiMessage: String
) {
    val landscape = LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE
    var showVolume by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val displayPrefs = remember(context) {
        context.getSharedPreferences("performer", android.content.Context.MODE_PRIVATE)
    }
    // One setting for portrait/landscape. Width is clamped independently by screen size.
    val paneFraction = remember(displayPrefs) {
        mutableFloatStateOf(displayPrefs.getFloat("trackPaneFraction", 0.5f).coerceIn(0.15f, 0.85f))
    }
    val itemHeight = if (landscape) 59.dp else 64.dp
    val dragThreshold = with(LocalDensity.current) { (itemHeight + 3.dp).toPx() }
    val keyboardHeight = when (keyboardMode) {
        KeyboardMode.HIDDEN -> 0.dp
        KeyboardMode.MONITOR -> if (landscape) 32.dp else 38.dp
        KeyboardMode.PLAY -> if (landscape) 76.dp else 116.dp
    }
    val anySolo = state.tracks.any { it.solo }
    val selectedTrack = state.tracks.firstOrNull { it.id == state.selectedId }
    val visibleSemitones = 24 // Touch PLAY must remain at least two octaves wide.
    var shownBase by remember(state.selectedId) {
        mutableIntStateOf(KeyboardOctaveRange.floorC(baseNote).coerceAtMost(96))
    }
    LaunchedEffect(baseNote, state.selectedId) {
        shownBase = KeyboardOctaveRange.floorC(baseNote).coerceAtMost(96)
    }
    LaunchedEffect(state.keyboardNotes, keyboardMode, state.selectedId) {
        if (keyboardMode == KeyboardMode.PLAY && !keyboard.hasActiveTouches) {
            val min = state.keyboardNotes.minOrNull()
            val max = state.keyboardNotes.maxOrNull()
            if (min != null && max != null) {
                var candidate = shownBase
                if (min < candidate) {
                    while (min < candidate) candidate -= 12
                } else if (max > candidate + visibleSemitones) {
                    while (max > candidate + visibleSemitones) candidate += 12
                }
                shownBase = KeyboardOctaveRange.floorC(candidate).coerceIn(0, 96)
            }
        }
    }
    val recordedRanges = state.tracks.filter { it.id in state.inputTargetIds }
        .mapNotNull { it.pitchRange }
    val monitorLower = (recordedRanges.map { it.first } +
        state.keyboardNotes.toList()).minOrNull()
    val monitorUpper = (recordedRanges.map { it.last } +
        state.keyboardNotes.toList()).maxOrNull()
    val monitoringRange = KeyboardOctaveRange.monitorBounds(
        monitorLower, monitorUpper, shownBase)
    val firstKey = if (keyboardMode == KeyboardMode.MONITOR)
        monitoringRange.first else shownBase.coerceIn(0, 96)
    val lastKey = if (keyboardMode == KeyboardMode.MONITOR)
        monitoringRange.last else firstKey + visibleSemitones
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
            horizontalArrangement = Arrangement.spacedBy(if (landscape) 8.dp else 2.dp)) {
            TransportButton(if (state.playing) "■" else "▶",
                if (state.playing) "停止" else "播放") { looper.playStop() }
            TransportButton(when {
                state.recording -> "■"
                state.armed -> "◎"
                else -> "●"
            }, "錄製", recording = state.recording || state.armed) { looper.recordPause() }
            MetronomeToggle(state.metronome) { looper.setMetronome(!state.metronome) }
            // BPM remains at its old position; TAP occupies the former Tap-record tile.
            Column(Modifier.width(63.dp).height(50.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text("${state.bpm} BPM", fontSize = 13.sp, maxLines = 1)
            }
            Column(Modifier.width(60.dp).height(50.dp).clickable { looper.tapTempo() },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceEvenly) {
                Text(if (state.tapCount == 0) "TAP" else "TAP ${state.tapCount}/4",
                    color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, maxLines = 1)
                Text(when {
                    state.countInBeatsLeft > 0 -> "預備${state.countInBeatsLeft}拍"
                    state.recording -> "錄製中"
                    state.armed -> "待錄"
                    else -> "打拍"
                }, color = if (state.recording || state.armed)
                    Color(0xFFF87171) else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    SmallAction("音量", { showVolume = true })
                    DropdownMenu(expanded = showVolume, onDismissRequest = { showVolume = false }) {
                        Column(Modifier.padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("主音量")
                            VerticalGainSlider(volume, onVolume)
                        }
                    }
                }
                VerticalLevelMeter(volume / 1.5f, masterPeak,
                    false, Modifier.height(43.dp))
            }
        }

        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val minLeft = minOf(178.dp, (maxWidth - 90.dp).coerceAtLeast(50.dp))
            val maxLeft = (maxWidth - 90.dp).coerceAtLeast(minLeft)
            val labelWidth = (maxWidth * paneFraction.floatValue).coerceIn(minLeft, maxLeft)
            val density = LocalDensity.current
            LazyColumn(Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                itemsIndexed(state.tracks, key = { _, track -> track.id }) { trackIndex, track ->
                    val isSelected = state.selectedId == track.id
                    val audible = !track.muted && (!anySolo || track.solo)
                    var dragPixels by remember(track.id) { mutableFloatStateOf(0f) }
                    var isDragging by remember(track.id) { mutableStateOf(false) }
                    var showTrackMenu by remember(track.id) { mutableStateOf(false) }
                    var showTrackVolume by remember(track.id) { mutableStateOf(false) }
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
                                Modifier.width(44.dp).fillMaxHeight()
                                    .clickable { looper.toggleInputTarget(track.id) }
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
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center) {
                                    Text("${trackIndex + 1}", fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (track.id in state.inputTargetIds)
                                            Color(0xFFEF4444) else MaterialTheme.colorScheme.onSurface)
                                    Box(Modifier.size(6.dp).background(
                                        if (track.id in state.inputTargetIds)
                                            Color(0xFFEF4444) else Color.Transparent, CircleShape))
                                }
                            }
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                Box(Modifier.fillMaxSize().clickable {
                                    if (isSelected) showTrackMenu = true
                                    else looper.selectTrack(track.id)
                                }, contentAlignment = Alignment.CenterStart) {
                                    Text(if (track.volume == 0) "靜音量！${track.name}" else
                                            track.name.ifBlank { track.category },
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
                                        onMidiSettings()
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
                            Box {
                                Box(Modifier.width(9.dp).height(itemHeight)
                                    .clickable { showTrackVolume = true },
                                    contentAlignment = Alignment.Center) {
                                    VerticalLevelMeter(track.volume / 127f,
                                        track.activityLevel * track.volume / 127f,
                                        !audible, Modifier.height(itemHeight - 5.dp))
                                }
                                DropdownMenu(expanded = showTrackVolume,
                                    onDismissRequest = { showTrackVolume = false }) {
                                    Column(Modifier.padding(10.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(track.name, maxLines = 1)
                                        if (track.volume == 0) Text("音量為 0：目前無聲")
                                        TextButton(onClick = { looper.setTrackVolume(track.id, 100) }) {
                                            Text("恢復預設音量 100")
                                        }
                                        VerticalGainSlider(track.volume.toFloat(),
                                            { looper.setTrackVolume(track.id, it.roundToInt()) }, 127f)
                                    }
                                }
                            }
                            Spacer(Modifier.width(2.dp))
                        }
                        PianoRoll(track, state.bars, state.beatsPerBar, audible,
                            Modifier.weight(1f).fillMaxHeight()
                                .clickable { looper.selectTrack(track.id) })
                    }
                }
                item(key = "add-track") {
                    Row(Modifier.fillMaxWidth().height(36.dp)
                        .clickable(onClick = onAddTrack),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(labelWidth).fillMaxHeight()
                            .background(MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center) {
                            Text("+", fontWeight = FontWeight.Bold, fontSize = 21.sp)
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
            // The separator is independent of the moving white playhead.
            // The drag target spans all track rows, the Add button and blank lane.
            Box(Modifier.offset(x = labelWidth - 11.dp).width(22.dp).fillMaxHeight()
                .zIndex(20f)
                .pointerInput(maxWidth) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val delta = with(density) { dragAmount.toDp().value }
                            val width = maxWidth.value.coerceAtLeast(1f)
                            paneFraction.floatValue = (paneFraction.floatValue + delta / width)
                                .coerceIn(minLeft.value / width, maxLeft.value / width)
                        },
                        onDragEnd = {
                            displayPrefs.edit().putFloat(
                                "trackPaneFraction", paneFraction.floatValue).apply()
                        }
                    )
                },
                contentAlignment = Alignment.BottomCenter) {
                Canvas(Modifier.fillMaxSize()) {
                    val x = size.width / 2f
                    drawLine(Color(0xFFA4ACBD), Offset(x, 0f), Offset(x, size.height),
                        strokeWidth = 1.dp.toPx())
                }
                Box(Modifier.padding(bottom = 5.dp).size(width = 22.dp, height = 34.dp)
                    .background(Color(0xFF374151), RoundedCornerShape(7.dp)),
                    contentAlignment = Alignment.Center) {
                    Text("↔", fontSize = 17.sp, color = Color.White)
                }
            }
        }

        Row(Modifier.fillMaxWidth().height(32.dp),
            verticalAlignment = Alignment.CenterVertically) {
            SmallAction(
                when (keyboardMode) {
                    KeyboardMode.PLAY -> "▤"
                    KeyboardMode.MONITOR -> "▥"
                    KeyboardMode.HIDDEN -> "▧"
                }, onKeyboardMode)
            Text(midiMessage, modifier = Modifier.weight(1f).padding(horizontal = 5.dp),
                fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.primary)
            if (keyboardMode == KeyboardMode.MONITOR) {
                Text("${MidiPitch.name(firstKey)}–${MidiPitch.name(lastKey)}", fontSize = 11.sp)
            } else if (keyboardMode == KeyboardMode.PLAY) {
                SmallAction("−", { onBaseNote((KeyboardOctaveRange.floorC(baseNote) - 12).coerceAtLeast(0)) })
                Text("${MidiPitch.name(firstKey)}–${MidiPitch.name(lastKey)}", fontSize = 10.sp)
                SmallAction("+", { onBaseNote((KeyboardOctaveRange.floorC(baseNote) + 12).coerceAtMost(96)) })
            }
        }
        if (keyboardMode != KeyboardMode.HIDDEN) {
            AndroidView(
                factory = { keyboard },
                update = {
                    it.configureRange(firstKey, lastKey)
                    it.velocity = velocity
                    it.readOnly = keyboardMode == KeyboardMode.MONITOR
                    it.showExternalNotes = state.keyboardNotes
                    it.showExternalVelocities = state.keyboardVelocities
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
