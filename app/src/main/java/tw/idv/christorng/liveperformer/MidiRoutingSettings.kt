package tw.idv.christorng.liveperformer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Project-level mappings. Source device/port/channel are physical inputs;
 * target channel is always assigned by the track. Keep this editor out of the stage view.
 */
@Composable
fun MidiRoutingSettings(
    state: LoopView,
    looper: LoopController,
    devices: List<MidiDeviceView>,
    onMessage: (String) -> Unit
) {
    var feedback by remember { mutableStateOf("") }
    var deviceKey by remember { mutableStateOf("*") }
    var channel by remember { mutableStateOf("2") }
    var kind by remember { mutableStateOf("note") }
    var first by remember { mutableStateOf("36") }
    var last by remember { mutableStateOf("43") }
    var action by remember { mutableStateOf("note") }
    var noteBase by remember { mutableStateOf("-1") }
    var targetTrack by remember(state.selectedId) {
        mutableIntStateOf(state.selectedId.takeIf { it != 0 } ?: state.tracks.firstOrNull()?.id ?: 0)
    }
    var deviceMenu by remember { mutableStateOf(false) }
    var kindMenu by remember { mutableStateOf(false) }
    var actionMenu by remember { mutableStateOf(false) }
    var trackMenu by remember { mutableStateOf(false) }

    HorizontalDivider()
    Text("MIDI 輸入路由（隨專案保存）", style = MaterialTheme.typography.titleMedium)
    Text("主鍵盤預設仍控制目前選取的音軌；以下規則可讓 Pad／CC 固定控制其他音軌。",
        style = MaterialTheme.typography.bodySmall)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            OutlinedButton(onClick = { deviceMenu = true }) {
                Text(if (deviceKey == "*") "來源：任何裝置" else
                    "來源：" + (devices.firstOrNull { it.key == deviceKey }?.name ?: "儲存的裝置"))
            }
            DropdownMenu(expanded = deviceMenu, onDismissRequest = { deviceMenu = false }) {
                DropdownMenuItem(text = { Text("任何裝置") }, onClick = {
                    deviceKey = "*"; deviceMenu = false
                })
                devices.filter { it.outputPorts > 0 }.forEach { device ->
                    DropdownMenuItem(text = { Text(device.name + " #" + device.id) }, onClick = {
                        deviceKey = device.key; deviceMenu = false
                    })
                }
            }
        }
        OutlinedButton(onClick = {
            feedback = if (!looper.applyMpkMiniMk2Preset(deviceKey))
                "請先建立鼓組及 Bass 音軌，或檢查路由上限"
            else "已設定 Pad A → 鼓組、Pad B → Bass（Channel 2）"
            onMessage(feedback)
        }) { Text("套用 MPK mini MK2 Pad 範例") }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = channel, onValueChange = { channel = it.filter(Char::isDigit).take(2) },
            label = { Text("Ch 1–16") }, singleLine = true, modifier = Modifier.width(95.dp))
        Box {
            OutlinedButton(onClick = { kindMenu = true }) {
                Text(when (kind) { "cc" -> "CC"; "program" -> "PC"; else -> "Note" })
            }
            DropdownMenu(expanded = kindMenu, onDismissRequest = { kindMenu = false }) {
                listOf("note" to "Note", "cc" to "CC", "program" to "Program Change").forEach { (value, title) ->
                    DropdownMenuItem(text = { Text(title) }, onClick = {
                        kind = value; kindMenu = false
                        if (kind == "cc" && action == "scale") action = "note"
                    })
                }
            }
        }
        OutlinedTextField(value = first, onValueChange = { first = it.filter(Char::isDigit).take(3) },
            label = { Text("起始編號") }, singleLine = true, modifier = Modifier.width(100.dp))
        OutlinedTextField(value = last, onValueChange = { last = it.filter(Char::isDigit).take(3) },
            label = { Text("結束編號") }, singleLine = true, modifier = Modifier.width(100.dp))
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            OutlinedButton(onClick = { trackMenu = true }) {
                Text(if (targetTrack == 0) "主輸出" else
                    state.tracks.firstOrNull { it.id == targetTrack }?.name ?: "選擇音軌")
            }
            DropdownMenu(expanded = trackMenu, onDismissRequest = { trackMenu = false }) {
                DropdownMenuItem(text = { Text("主輸出（僅總音量）") }, onClick = {
                    targetTrack = 0; trackMenu = false
                })
                state.tracks.forEach { track ->
                    DropdownMenuItem(text = { Text(track.name + "（Ch " + (track.channel + 1) + "）") },
                        onClick = { targetTrack = track.id; trackMenu = false })
                }
            }
        }
        Box {
            OutlinedButton(onClick = { actionMenu = true }) {
                Text(when (action) {
                    "scale" -> "音階彈奏"
                    "volume" -> "軌音量"
                    "pan" -> "左右平衡"
                    "master" -> "總音量"
                    "select-track" -> "切換音軌"
                    else -> "音符彈奏"
                })
            }
            DropdownMenu(expanded = actionMenu, onDismissRequest = { actionMenu = false }) {
                val options = if (kind == "cc") listOf(
                    "note" to "CC → 音符（按下 64–127，放開 0–63）",
                    "scale" to "CC → 音階",
                    "volume" to "音軌音量", "pan" to "左右平衡", "master" to "總音量"
                ) else if (kind == "program") listOf("select-track" to "切換音軌")
                else listOf("note" to "原音／轉調", "scale" to "音階彈奏")
                options.forEach { (value, title) ->
                    DropdownMenuItem(text = { Text(title) },
                        onClick = { action = value; actionMenu = false })
                }
            }
        }
        if (action == "note" || action == "scale") {
            OutlinedTextField(value = noteBase, onValueChange = {
                noteBase = it.filter { c -> c.isDigit() || c == '-' }.take(4)
            }, label = { Text(if (action == "scale") "主音 MIDI" else "目標首音 (-1 原音)") },
                singleLine = true, modifier = Modifier.width(145.dp))
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            val ch = channel.toIntOrNull()?.minus(1) ?: -100
            val start = first.toIntOrNull() ?: -1
            val end = last.toIntOrNull() ?: -1
            val base = noteBase.toIntOrNull() ?: -1
            val valid = (action == "master" && targetTrack == 0) ||
                (action != "master" && targetTrack != 0)
            if (!valid || !looper.addMidiRoute(deviceKey, ch, kind, start, end,
                    targetTrack, action, base)) {
                feedback = "MIDI 路由設定無效：請確認 Channel、範圍與目標音軌"
            } else feedback = "已新增 MIDI 路由"
            onMessage(feedback)
        }) { Text("新增路由") }
        Text("CC Pad 建議使用 Momentary；不相容的裝置可改用通用編號設定。",
            style = MaterialTheme.typography.bodySmall)
    }
    if (feedback.isNotEmpty()) Text(feedback, style = MaterialTheme.typography.bodySmall)
    state.midiRoutes.forEach { route ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val dev = if (route.deviceKey == "*") "所有" else
                devices.firstOrNull { it.key == route.deviceKey }?.name ?: "離線裝置"
            val target = state.tracks.firstOrNull { it.id == route.trackId }?.name ?: "主音量"
            Text(dev + " / Ch " + (route.channel + 1) + " / " + route.kind.uppercase() +
                " " + route.first + "–" + route.last + " → " + target +
                " (" + route.action + (if (route.noteBase >= 0) " " + route.noteBase else "") + ")",
                modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { looper.removeMidiRoute(route.id) }) { Text("移除") }
        }
    }
    if (state.midiRoutes.isEmpty()) Text("尚未設定固定路由。", style = MaterialTheme.typography.bodySmall)
}

@Composable
fun MidiSystemPanel(
    devices: List<MidiDeviceView>,
    messages: List<String>,
    clockTargetId: Int?,
    onClockTarget: (Int?) -> Unit
) {
    HorizontalDivider()
    Text("MIDI 裝置／診斷", style = MaterialTheme.typography.titleMedium)
    if (devices.isEmpty()) Text("尚未偵測到 MIDI 裝置，請確認 USB Host 與供電。")
    devices.forEach { d ->
        Text(d.name + "（ID " + d.id + "）" +
            " · 輸入到 App：" + d.outputPorts + " 埠" +
            " · App 可輸出：" + d.inputPorts + " 埠",
            style = MaterialTheme.typography.bodySmall)
    }
    Text("最新五筆 MIDI（由新到舊）", style = MaterialTheme.typography.titleSmall)
    if (messages.isEmpty()) Text("尚未收到 MIDI 訊息。", style = MaterialTheme.typography.bodySmall)
    messages.forEach { item -> Text(item, style = MaterialTheme.typography.bodySmall) }
    Text("MIDI Clock 輸出（實驗性 24 PPQN）", style = MaterialTheme.typography.titleSmall)
    Row(Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = clockTargetId == null, onClick = { onClockTarget(null) },
            label = { Text("關閉") })
        devices.filter { it.inputPorts > 0 }.forEach { d ->
            FilterChip(selected = clockTargetId == d.id, onClick = { onClockTarget(d.id) },
                label = { Text(d.name + " #" + d.id) })
        }
    }
    Text("選擇裝置後，開始／停止播放會送 MIDI Start／Stop，播放期間送 Clock。MPK mini MK2 必須在 Editor 將 Arpeggiator CLOCK 設為 External 並 Send To RAM；目前時序受 Android 排程精度限制。",
        style = MaterialTheme.typography.bodySmall)
}
