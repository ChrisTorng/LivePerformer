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
    var ccMode by remember { mutableStateOf("press-toggle") }
    var knobFirstCc by remember { mutableIntStateOf(looper.defaultKnobFirstCc("*")) }
    var confirmControlsPreset by remember { mutableStateOf(false) }
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
        ccMode = route.ccMode
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
        Text("Note 模式：Bank A 八鼓、Bank B Bass 八音音階；硬體 Pad 保持 Momentary。",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
            feedback = if (looper.applyMpkMiniMk2Preset(deviceKey))
                "已套用 Note A 鼓組與 Note B Bass 音階（C2 大調）；可編輯 Bass 的調性與八度"
            else "請先建立鼓組音軌；若已有自訂 Note 路由，請自行編輯"
            onMessage(feedback)
        }) { Text("套用 Note A 鼓組 / Note B Bass", maxLines = 1) }
        Text("CC 模式：Pad 以 Momentary 發送 127／0，App 在每次按下時切換開關。" +
            " Bank A：播放、錄製、節拍器、前五軌 Solo；" +
            " Bank B：第 1–7 軌 Mute、全部 Mute。",
            style = MaterialTheme.typography.bodySmall)
        Text("Knob MIDI CC 起點（連續音量，Keybed/Controls Channel 1）",
            style = MaterialTheme.typography.labelLarge)
        MidiChoice(if (knobFirstCc == 1) "CC 1–8（少改 Editor；搖桿 CC1 可能重複）"
                   else "CC 70–77（建議獨立設定）",
            listOf("1" to "CC 1–8（原範例；注意搖桿 CC1）",
                "70" to "CC 70–77（建議，需在 Akai Editor 調整）"),
            { knobFirstCc = it.toInt() }, Modifier.fillMaxWidth())
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
            confirmControlsPreset = true
        }) { Text("套用 CC A/B 控制與 8 Knob 音量", maxLines = 1) }
        if (confirmControlsPreset) {
            AlertDialog(
                onDismissRequest = { confirmControlsPreset = false },
                title = { Text("更新 MPK 控制預設") },
                text = { Text("將取代此裝置的 Pad CC 20–35（Channel 2）與" +
                    " Knob CC ${knobFirstCc}–${knobFirstCc + 7}（Channel 1）" +
                    "既有路由；Note 鼓組、Bass 音階與其他路由不變。" +
                    "所有項目仍可再逐條編輯。") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmControlsPreset = false
                        feedback = if (looper.applyMpkMiniMk2ControlsPreset(deviceKey, knobFirstCc))
                            "已安裝 CC A/B Toggle 控制與 8 Knob 音量對應"
                        else "MIDI 路由容量不足，無法套用預設"
                        onMessage(feedback)
                    }) { Text("取代舊 CC 設定") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmControlsPreset = false }) { Text("取消") }
                }
            )
        }
        HorizontalDivider()
        Text(if (editingId == null) "新增 MIDI 路由" else "編輯第 " + editingId + " 條路由",
            style = MaterialTheme.typography.titleMedium)
        Text("MIDI 來源", style = MaterialTheme.typography.labelLarge)
        MidiChoice(if (deviceKey == "*") "任何 MIDI 裝置" else
            devices.firstOrNull { it.key == deviceKey }?.name ?: "已儲存的離線裝置",
            listOf("*" to "任何 MIDI 裝置") + devices.filter { it.outputPorts > 0 }
                .map { it.key to (it.name + " #" + it.id) },
            { deviceKey = it; knobFirstCc = looper.defaultKnobFirstCc(it) }, Modifier.fillMaxWidth())

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
                    ccMode = if (it == "cc") "press-toggle" else "legacy"
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
                "drum-pads" to "8 Pad 固定鼓聲功能",
                "volume" to "指定音軌音量", "pan" to "左右平衡", "master" to "總音量",
                "slot-volume" to "依音軌位置調整音量（Knob）",
                "mute" to "指定軌 Mute", "solo" to "指定軌 Solo",
                "slot-mute" to "第 1–7 軌 Mute",
                "slot-solo" to "第 1–7 軌 Solo",
                "mute-all" to "全部音軌 Mute",
                "play-stop" to "播放／停止",
                "record-toggle" to "錄製／結束",
                "metronome-toggle" to "節拍器開關",
                "next-track" to "選取下一軌（Momentary）")
            else -> listOf("note" to "原始／半音音高",
                "scale" to "調性音階", "custom" to "逐鍵指定",
                "drum-pads" to "8 Pad 固定鼓聲功能")
        }
        MidiChoice(actions.firstOrNull { it.first == action }?.second ?: "選擇動作",
            actions, { selected ->
                action = selected
                if (selected in setOf("play-stop", "record-toggle",
                        "metronome-toggle", "next-track", "master", "mute-all",
                        "slot-volume", "slot-mute", "slot-solo")) targetTrack = 0
                else if (targetTrack == 0) targetTrack =
                    state.selectedId.takeIf { it != 0 } ?: state.tracks.firstOrNull()?.id ?: 0
                if (selected in setOf("slot-volume", "slot-mute", "slot-solo") &&
                    noteBase.toIntOrNull() !in 0..6) noteBase = "0"
                if (selected == "custom" && custom.size != makeScale().size) {
                    custom.clear()
                    custom.addAll(makeScale())
                }
            }, Modifier.fillMaxWidth())

        if (kind == "cc" && action in setOf("play-stop", "record-toggle",
                "metronome-toggle", "mute", "solo", "slot-mute", "slot-solo", "mute-all")) {
            MidiChoice(when (ccMode) {
                    "absolute" -> "硬體 Toggle（127 開／0 關）"
                    "momentary" -> "Momentary（按下執行）"
                    "legacy" -> "沿用舊版行為"
                    else -> "軟體 Toggle（建議）"
                },
                listOf("press-toggle" to "軟體 Toggle：每按一下切換（硬體用 Momentary）",
                    "absolute" to "硬體 Toggle：CC 127 開、CC 0 關",
                    "momentary" to "Momentary：按下執行",
                    "legacy" to "沿用舊版行為"),
                { ccMode = it }, Modifier.fillMaxWidth())
        }
        if (action in setOf("slot-volume", "slot-mute", "slot-solo")) {
            MidiChoice("音軌位置 " + ((noteBase.toIntOrNull() ?: 0) + 1),
                (0..6).map { it.toString() to ("第 " + (it + 1) + " 軌") },
                { noteBase = it }, Modifier.fillMaxWidth())
            Text("跟隨演奏畫面上的音軌排序，尚未建立的軌道暫不執行。",
                style = MaterialTheme.typography.bodySmall)
        }
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

        if (action in setOf("note", "scale", "custom", "drum-pads")) {
            val start = first.toIntOrNull() ?: -1
            val end = last.toIntOrNull() ?: -1
            if (start in 0..127 && end in start..127) {
                val base = (octave + 1) * 12 + key
                val preview = (start..end.coerceAtMost(start + 7)).mapIndexed { i, input ->
                    val pitch = when (action) {
                        "scale" -> MidiPitch.scale(base, i, scaleMode)
                        "drum-pads" -> DrumPads.gmNotes.getOrNull(i) ?: -1
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
                targetTrack, action, pitch, scaleMode, customMap, ccMode)
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
        Text("MPK mini MKII 的 Note／CC 共用硬體 Pad Toggle 設定。建議硬體維持 Momentary，" +
            "由 App 將 CC 20–35 解讀為軟體 Toggle，以免 Bass 音符鎖住。Knob CC 起點可在上方選擇。",
            style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("已儲存的 MIDI 路由：" + state.midiRoutes.size,
            style = MaterialTheme.typography.titleMedium)
        if (state.midiRoutes.isEmpty()) Text("目前沒有固定路由。")
        state.midiRoutes.forEach { route ->
            val device = if (route.deviceKey == "*") "任何裝置" else
                devices.firstOrNull { it.key == route.deviceKey }?.name ?: "離線裝置"
            val name = if (route.action in setOf("slot-volume", "slot-mute", "slot-solo"))
                "第 ${route.noteBase + 1} 軌"
                else if (route.trackId == 0) "全域／總控"
                else state.tracks.firstOrNull { it.id == route.trackId }?.name ?: "已刪除音軌"
            Text(device + " / Ch " + (route.channel + 1) + " / " +
                route.kind.uppercase() + " " + route.first + "–" + route.last,
                style = MaterialTheme.typography.bodySmall)
            Text("→ " + name + " · " + when (route.action) {
                "scale" -> MidiPitch.name(route.noteBase) +
                    if (route.scaleMode == "minor") " 自然小調" else " 大調"
                "custom" -> "逐鍵指定"
                "drum-pads" -> "固定 8 Pad 鼓聲功能"
                "slot-volume" -> "位置音量"
                "slot-mute" -> "位置靜音"
                "slot-solo" -> "位置獨奏"
                "mute-all" -> "全部靜音"
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
    messagesByDevice: Map<Int, List<String>>,
    clockTargetId: Int?,
    onClockTarget: (Int?) -> Unit
) {
    Text("MIDI 裝置／測試", style = MaterialTheme.typography.titleMedium)
    if (devices.isEmpty()) Text("尚未偵測到 MIDI 裝置，請確認 USB Host 與供電。")
    devices.forEach { device ->
        Text(device.name + "（ID " + device.id + "）" +
            " · 輸入到 App：" + device.outputPorts + " 埠" +
            " · App 輸出：" + device.inputPorts + " 埠",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = messagesByDevice[device.id].orEmpty().joinToString("\n"),
            onValueChange = {},
            readOnly = true,
            label = { Text("MIDI 訊息（最新在上，最多 50 筆）") },
            minLines = 4,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth()
        )
    }
    HorizontalDivider()
    Text("MIDI Clock 輸出（實驗性 24 PPQN）", style = MaterialTheme.typography.titleSmall)
    Row(Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = clockTargetId == null, onClick = { onClockTarget(null) },
            label = { Text("停用") })
        devices.filter { it.inputPorts > 0 }.forEach { device ->
            FilterChip(selected = clockTargetId == device.id,
                onClick = { onClockTarget(device.id) },
                label = { Text(device.name) })
        }
    }
    Text("選擇裝置後，開始／停止播放會送 MIDI Start／Stop，播放期間送 Clock。MPK mini MK2 必須在 Editor 將 Arpeggiator CLOCK 設為 External 並 Send To RAM；目前時序受 Android 排程精度限制。",
        style = MaterialTheme.typography.bodySmall)
}
