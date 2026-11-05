package tw.idv.christorng.liveperformer

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Menus occupy full lines on a phone and never squeeze a neighbouring field. */
@Composable
private fun MidiChoice(
    label: String, options: List<Pair<String, String>>, onPick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { entry ->
                DropdownMenuItem(text = { Text(entry.second) }, onClick = {
                    expanded = false
                    onPick(entry.first)
                })
            }
        }
    }
}

@Composable
fun MidiRoutingSettings(
    state: LoopView, looper: LoopController, devices: List<MidiDeviceView>,
    onMessage: (String) -> Unit
) {
    var feedback by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf<Int?>(null) }
    var deviceKey by remember { mutableStateOf("*") }
    var channel by remember { mutableStateOf("2") }
    var kind by remember { mutableStateOf("note") }
    var first by remember { mutableStateOf("36") }
    var last by remember { mutableStateOf("43") }
    var action by remember { mutableStateOf("note") }
    var noteBase by remember { mutableStateOf("-1") }
    var scaleMode by remember { mutableStateOf("major") }
    var key by remember { mutableIntStateOf(0) }
    var octave by remember { mutableIntStateOf(2) }
    var targetTrack by remember { mutableIntStateOf(state.selectedId) }
    val custom = remember { mutableStateListOf<String>() }

    fun makeScale(): List<String> {
        val size = ((last.toIntOrNull() ?: 0) - (first.toIntOrNull() ?: 0) + 1).coerceIn(0, 16)
        val root = (octave + 1) * 12 + key
        return List(size) { MidiPitch.scale(root, it, scaleMode).coerceAtMost(127).toString() }
    }
    fun load(route: MidiRoute) {
        editingId = route.id
        deviceKey = route.deviceKey
        channel = (route.channel + 1).toString()
        kind = route.kind
        first = route.first.toString()
        last = route.last.toString()
        targetTrack = route.trackId
        action = route.action
        noteBase = route.noteBase.toString()
        scaleMode = route.scaleMode
        val root = if (route.noteBase >= 0) route.noteBase else 36
        key = root % 12
        octave = (root / 12 - 1).coerceIn(0, 7)
        custom.clear()
        custom.addAll(route.noteMap.map { it.toString() })
        feedback = "編輯第 " + route.id + " 條路由"
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("主鍵盤仍彈奏選取音軌；Pad 和 CC 可鎖定至其他音軌。",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
            feedback = if (looper.applyMpkMiniMk2Preset(deviceKey))
                "已套用 Pad：Note 36–43 → 鼓，44–51 → Bass"
            else "請先建立鼓組與 Bass 音軌"
            onMessage(feedback)
        }) { Text("套用 MPK mini MK2 範例", maxLines = 1) }
        HorizontalDivider()
        Text(if (editingId == null) "新增 MIDI 路由" else "編輯第 " + editingId + " 條路由",
            style = MaterialTheme.typography.titleMedium)
        Text("MIDI 來源", style = MaterialTheme.typography.labelLarge)
        MidiChoice(if (deviceKey == "*") "任何 MIDI 裝置" else
            devices.firstOrNull { it.key == deviceKey }?.name ?: "已儲存的離線裝置",
            listOf("*" to "任何 MIDI 裝置") + devices.filter { it.outputPorts > 0 }
                .map { it.key to (it.name + " #" + it.id) },
            { deviceKey = it }, Modifier.fillMaxWidth())

        Text("輸入 Channel／訊息", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(channel, onValueChange = {
                channel = it.filter(Char::isDigit).take(2)
            }, label = { Text("Ch 1–16", maxLines = 1) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f))
            MidiChoice(when (kind) { "cc" -> "CC"; "program" -> "PC"; else -> "Note" },
                listOf("note" to "Note", "cc" to "CC", "program" to "Program Change"),
                {
                    kind = it
                    action = if (it == "program") "select-track" else "note"
                    if (it == "cc" && first == "36") {
                        first = "20"; last = "27"; noteBase = "36"
                    }
                }, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(first, onValueChange = {
                first = it.filter(Char::isDigit).take(3)
            }, label = { Text("起始編號", maxLines = 1) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f))
            OutlinedTextField(last, onValueChange = {
                last = it.filter(Char::isDigit).take(3)
            }, label = { Text("結束編號", maxLines = 1) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f))
        }
        Text("目標音軌", style = MaterialTheme.typography.labelLarge)
        MidiChoice(state.tracks.firstOrNull { it.id == targetTrack }?.name ?:
            if (targetTrack == 0) "主音量" else "請選擇音軌",
            listOf("0" to "主音量") +
                state.tracks.map { it.id.toString() to
                    (it.name + "（Ch " + (it.channel + 1) + "）") },
            { targetTrack = it.toInt() }, Modifier.fillMaxWidth())

        Text("映射方式", style = MaterialTheme.typography.labelLarge)
        val actions = when (kind) {
            "program" -> listOf("select-track" to "切換音軌")
            "cc" -> listOf("note" to "CC → 指定首音（半音）",
                "scale" to "CC → 調性音階", "custom" to "CC → 逐鍵指定",
                "volume" to "音軌音量", "pan" to "左右平衡", "master" to "總音量")
            else -> listOf("note" to "原始／半音音高",
                "scale" to "調性音階", "custom" to "逐鍵指定")
        }
        MidiChoice(actions.firstOrNull { it.first == action }?.second ?: "選擇動作",
            actions, { selected ->
                action = selected
                if (selected == "custom" && custom.size != makeScale().size) {
                    custom.clear()
                    custom.addAll(makeScale())
                }
            }, Modifier.fillMaxWidth())

        if (action == "note") {
            OutlinedTextField(noteBase, onValueChange = {
                noteBase = it.filter { c -> c.isDigit() || c == '-' }.take(4)
            }, label = { Text("目標首音（-1 = 保留原音）") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth())
        }
        if (action == "scale" || action == "custom") {
            Text("調性／音域（只影響本條路由）",
                style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MidiChoice("主音 " + MidiPitch.names[key],
                    MidiPitch.names.mapIndexed { i, name -> i.toString() to name },
                    { key = it.toInt() }, Modifier.weight(1f))
                MidiChoice(if (scaleMode == "major") "大調" else "自然小調",
                    listOf("major" to "大調", "minor" to "自然小調"),
                    { scaleMode = it }, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("首音 " + MidiPitch.names[key] + octave,
                    modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { octave = (octave - 1).coerceAtLeast(0) }) {
                    Text("−")
                }
                OutlinedButton(onClick = { octave = (octave + 1).coerceAtMost(7) }) {
                    Text("+")
                }
            }
        }
        if (action == "custom") {
            Text("逐鍵音高：-1 表示停用。最多一次 16 鍵。",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                custom.clear()
                custom.addAll(makeScale())
            }, modifier = Modifier.fillMaxWidth()) { Text("依目前調性填入各鍵") }
            val start = first.toIntOrNull() ?: 0
            val end = last.toIntOrNull() ?: -1
            val count = (end - start + 1).coerceIn(0, 16)
            repeat((count + 1) / 2) { line ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (column in 0..1) {
                        val i = line * 2 + column
                        if (i < count) {
                            val value = custom.getOrNull(i).orEmpty()
                            OutlinedTextField(value, onValueChange = {
                                while (custom.size <= i) custom.add("")
                                custom[i] = it.filter { c -> c.isDigit() || c == '-' }.take(4)
                            }, label = { Text((start + i).toString() + " → " +
                                MidiPitch.name(value.toIntOrNull() ?: -1), maxLines = 1) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f))
                        } else Spacer(Modifier.weight(1f))
                    }
                }
            }
            if (end - start + 1 > 16) Text("逐鍵模式範圍最多 16，請拆成多條路由。",
                color = MaterialTheme.colorScheme.error)
        }

        if (action in setOf("note", "scale", "custom")) {
            val start = first.toIntOrNull() ?: -1
            val end = last.toIntOrNull() ?: -1
            if (start in 0..127 && end in start..127) {
                val base = (octave + 1) * 12 + key
                val preview = (start..end.coerceAtMost(start + 7)).mapIndexed { i, input ->
                    val pitch = when (action) {
                        "scale" -> MidiPitch.scale(base, i, scaleMode)
                        "custom" -> custom.getOrNull(i)?.toIntOrNull() ?: -1
                        else -> (noteBase.toIntOrNull() ?: -1).let { if (it < 0) input else it + i }
                    }
                    input.toString() + " → " + MidiPitch.name(pitch) + " (" + pitch + ")"
                }
                Text("預覽：" + preview.joinToString("  "),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        Button(onClick = {
            val ch = channel.toIntOrNull()?.minus(1) ?: -100
            val start = first.toIntOrNull() ?: -1
            val end = last.toIntOrNull() ?: -1
            val pitch = if (action == "scale" || action == "custom") (octave + 1) * 12 + key
                else noteBase.toIntOrNull() ?: -2
            val customMap = if (action == "custom") custom.map { it.toIntOrNull() ?: -2 }
                else emptyList()
            val ok = looper.putMidiRoute(editingId, deviceKey, ch, kind, start, end,
                targetTrack, action, pitch, scaleMode, customMap)
            feedback = if (ok) "路由已儲存至專案" else
                "無效路由：請確認 Ch、編號、音高與目標音軌。"
            if (ok) editingId = null
            onMessage(feedback)
        }, modifier = Modifier.fillMaxWidth()) {
            Text(if (editingId == null) "新增路由" else "儲存修改")
        }
        if (editingId != null) {
            OutlinedButton(onClick = { editingId = null; feedback = "" },
                modifier = Modifier.fillMaxWidth()) { Text("取消編輯") }
        }
        if (feedback.isNotBlank()) Text(feedback, style = MaterialTheme.typography.bodySmall)
        Text("CC 20 是控制器編號，不是音高。若用 CC Pad 彈奏，請在硬體使用 Momentary 模式：按下 127、放開 0。",
            style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("已儲存的 MIDI 路由：" + state.midiRoutes.size,
            style = MaterialTheme.typography.titleMedium)
        if (state.midiRoutes.isEmpty()) Text("目前沒有固定路由。")
        state.midiRoutes.forEach { route ->
            val device = if (route.deviceKey == "*") "任何裝置" else
                devices.firstOrNull { it.key == route.deviceKey }?.name ?: "離線裝置"
            val name = state.tracks.firstOrNull { it.id == route.trackId }?.name ?: "主音量"
            Text(device + " / Ch " + (route.channel + 1) + " / " +
                route.kind.uppercase() + " " + route.first + "–" + route.last,
                style = MaterialTheme.typography.bodySmall)
            Text("→ " + name + " · " + when (route.action) {
                "scale" -> MidiPitch.name(route.noteBase) +
                    if (route.scaleMode == "minor") " 自然小調" else " 大調"
                "custom" -> "逐鍵指定"
                "note" -> if (route.noteBase < 0) "原音" else "首音 " + MidiPitch.name(route.noteBase)
                else -> route.action
            }, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { load(route) }, modifier = Modifier.weight(1f)) { Text("編輯") }
                OutlinedButton(onClick = {
                    if (editingId == route.id) editingId = null
                    looper.removeMidiRoute(route.id)
                }, modifier = Modifier.weight(1f)) { Text("移除") }
            }
            HorizontalDivider()
        }
    }
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
