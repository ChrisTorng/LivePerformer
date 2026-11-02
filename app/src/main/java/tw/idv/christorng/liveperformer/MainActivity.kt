package tw.idv.christorng.liveperformer

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.os.Build
import androidx.core.view.WindowCompat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15 enforces edge-to-edge; Compose applies the safe insets explicitly.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        setContent {
            val context = LocalContext.current
            val darkMode = isSystemInDarkTheme()
            val colors = remember(darkMode) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (darkMode) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                } else {
                    if (darkMode) darkColorScheme() else lightColorScheme()
                }
            }
            SideEffect {
                val bars = WindowCompat.getInsetsController(window, window.decorView)
                bars.isAppearanceLightStatusBars = !darkMode
                bars.isAppearanceLightNavigationBars = !darkMode
            }
            MaterialTheme(colorScheme = colors) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PerformerScreen()
                }
            }
        }
    }
}

private fun trackAccent(channel: Int): Color = when (channel) {
    9 -> Color(0xFFF87171)
    1 -> Color(0xFFFBBF24)
    2 -> Color(0xFF4ADE80)
    3 -> Color(0xFF60A5FA)
    else -> Color(0xFFC084FC)
}

@Composable
private fun LoopTimeline(loop: LoopView, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("1", style = MaterialTheme.typography.labelSmall)
            Text("${loop.bar}.${loop.beat}", style = MaterialTheme.typography.labelSmall)
            Text("${loop.bars}", style = MaterialTheme.typography.labelSmall)
        }
        Canvas(modifier = Modifier.fillMaxWidth().height(28.dp)) {
            val corner = androidx.compose.ui.geometry.CornerRadius(9.dp.toPx())
            drawRoundRect(Color(0xFF1F2937), cornerRadius = corner)
            drawRoundRect(
                if (loop.recording) Color(0xFFEF4444) else Color(0xFF3B82F6),
                size = androidx.compose.ui.geometry.Size(size.width * loop.progress, size.height),
                cornerRadius = corner, alpha = .58f
            )
            for (step in 1 until loop.bars * 4) {
                val x = size.width * step / (loop.bars * 4)
                drawLine(
                    if (step % 4 == 0) Color.White.copy(alpha = .75f)
                    else Color.White.copy(alpha = .27f),
                    Offset(x, 1.dp.toPx()), Offset(x, size.height - 1.dp.toPx()),
                    strokeWidth = if (step % 4 == 0) 2.dp.toPx() else 1.dp.toPx()
                )
            }
            val x = (size.width * loop.progress).coerceIn(0f, size.width)
            drawLine(Color(0xFFECFEFF), Offset(x, 0f), Offset(x, size.height),
                strokeWidth = 3.dp.toPx())
        }
    }
}

@Composable
private fun TrackPattern(track: LoopTrackView, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val color = trackAccent(track.channel)
        drawRoundRect(color.copy(alpha = .13f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()))
        drawLine(Color.White.copy(alpha = .22f),
            Offset(size.width / 2, 0f), Offset(size.width / 2, size.height),
            strokeWidth = 1.dp.toPx())
        for (hit in track.hits) {
            val x = (hit * size.width).coerceIn(0f, size.width - 2.dp.toPx())
            val w = (size.width / 30).coerceAtLeast(2.dp.toPx())
            drawRoundRect(if (track.muted) Color.Gray else color,
                topLeft = Offset(x, size.height * .22f), 
                size = androidx.compose.ui.geometry.Size(w, size.height * .56f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()))
        }
    }
}

private fun loadBundledFont(context: Context): File {
    val dst = File(context.filesDir, "GeneralUser-GS.sf2")
    if (!dst.exists() || dst.length() < 10_000_000) {
        val temp = File(context.filesDir, "GeneralUser-GS.part")
        context.assets.open("GeneralUser-GS.sf2").use { source ->
            temp.outputStream().use { source.copyTo(it) }
        }
        if (!temp.renameTo(dst)) error("Cannot install bundled GM SoundFont")
    }
    return dst
}

private fun audioOutputName(device: AudioDeviceInfo): String {
    val type = when (device.type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "手機喇叭"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "3.5mm 耳機"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "3.5mm 耳麥"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB 音訊介面"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 耳機"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "藍牙音訊"
        else -> "輸出裝置"
    }
    return "$type · ${device.productName}（ID ${device.id}）"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PerformerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val library = remember(context) { SoundFontLibrary(context) }
    val settings = remember(context) { context.getSharedPreferences("performer", Context.MODE_PRIVATE) }
    val audioManager = remember(context) { context.getSystemService(AudioManager::class.java) }
    val looper = remember(context) { LoopController(context) }
    val loopState by looper.state.collectAsState()
    val keyboard = remember(context, looper) {
        PianoKeyboardView(context).apply { sendMidi = looper::onMidi }
    }
    val fonts = remember { mutableStateListOf<SoundFontItem>() }
    val presets = remember { mutableStateListOf<InstrumentPreset>() }
    var selectedFontId by remember { mutableStateOf("gm") }
    var presetIndex by remember { mutableIntStateOf(0) }
    var showFonts by remember { mutableStateOf(false) }
    var showPresets by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showInstrumentPicker by remember { mutableStateOf(false) }
    var showBarsMenu by remember { mutableStateOf(false) }
    var showDemoConfirmation by remember { mutableStateOf(false) }
    var openTrackMenu by remember { mutableStateOf<Int?>(null) }
    var deleteCandidate by remember { mutableStateOf<SoundFontItem?>(null) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("啟動音訊中…") }
    var audioStatus by remember { mutableStateOf("Audio: starting…") }
    var midiStatus by remember { mutableStateOf("USB MIDI: scanning…") }
    var baseNote by remember { mutableIntStateOf(settings.getInt("baseNote", 48).coerceIn(0, 96)) }
    var velocity by remember { mutableFloatStateOf(settings.getFloat("velocity", 100f).coerceIn(1f, 127f)) }
    var gain by remember { mutableFloatStateOf(settings.getFloat("gain", 0.8f).coerceIn(0.1f, 1.5f)) }
    var routeRevision by remember { mutableIntStateOf(0) }
    var requestedOutputId by remember { mutableIntStateOf(0) } // Device IDs are ephemeral: do not persist.
    var outputs by remember { mutableStateOf(emptyList<AudioDeviceInfo>()) }
    var showOutputs by remember { mutableStateOf(false) }
    var restarting by remember { mutableStateOf(false) }
    var measuring by remember { mutableStateOf(false) }
    var testCount by remember { mutableIntStateOf(3) }
    var measurement by remember { mutableStateOf("尚未量測。需以喇叭或有線 Loopback 讓麥克風收到測試訊號。") }

    suspend fun activate(item: SoundFontItem) {
        if (loading) return
        loading = true
        message = "載入 ${item.title} 中…"
        try {
            // Reading SF2 headers and creating the large synth remain off the UI/audio threads.
            val result = withContext(Dispatchers.IO) {
                val info = SoundFontPresets.read(item.file)
                val ok = NativeEngine.loadSf2(item.file.absolutePath)
                ok to info
            }
            if (!result.first) {
                message = "${item.title} 載入失敗；保留原音色庫。"
            } else {
                fonts.clear()
                fonts.addAll(library.all())
                selectedFontId = item.id
                presets.clear()
                presets.addAll(result.second.presets)
                if (presets.isEmpty() && item.isBundled)
                    presets.addAll(GmPrograms.names.mapIndexed { i, n -> InstrumentPreset(0, i, n) })
                val savedBank = settings.getInt("presetBank:" + item.id, 0)
                val savedProgram = settings.getInt("presetProgram:" + item.id, 0)
                presetIndex = presets.indexOfFirst { it.bank == savedBank && it.program == savedProgram }
                    .takeIf { it >= 0 } ?: 0
                if (presets.isNotEmpty()) {
                    val p = presets.first()
                    NativeEngine.selectPreset(p.bank, p.program)
                }
                library.rememberSelected(item.id)
                looper.refreshPrograms()
                message = "${item.title} 已切換 · ${presets.size} 個音色"
            }
        } catch (ex: Exception) {
            message = "切換音色庫失敗：${ex.message}"
        } finally {
            loading = false
        }
    }

    fun scanAudioOutputs() {
        outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.isSink }.sortedBy { it.id }
        if (requestedOutputId != 0 && outputs.none { it.id == requestedOutputId }) {
            requestedOutputId = 0
        }
    }

    // Audio route hotplug and AAudio stream disconnect are handled outside the audio callback.
    suspend fun reconnectAudio() {
        if (restarting) return
        restarting = true
        try {
            while (loading || measuring) delay(100)
            keyboard.allNotesOff()
            val font = fonts.firstOrNull { it.id == selectedFontId }
            val ready = withContext(Dispatchers.IO) { NativeEngine.restart(requestedOutputId) }
            if (!ready) {
                message = "音訊重新連線失敗，請選擇自動輸出或重新連接。"
                return
            }
            NativeEngine.setGain(gain)
            if (font != null) activate(font)
            else message = "音訊已重新連線；尚未找到音色庫。"
        } catch (ex: Exception) {
            message = "音訊重新連線失敗：${ex.message}"
        } finally {
            restarting = false
        }
    }

    fun runMeasurement() {
        if (measuring) return
        scope.launch {
            measuring = true
            val before = NativeEngine.xrunCount()
            val results = mutableListOf<String>()
            try {
                for (i in 1..testCount) {
                    measurement = "正在量測 $i / $testCount 次…（測試期間可能增加 xRuns）"
                    results += withContext(Dispatchers.IO) { PulseLatencyTester.measure() }
                }
                val after = NativeEngine.xrunCount()
                measurement = PulseLatencyTester.summarize(results, before, after)
            } catch (ex: Exception) {
                measurement = "量測失敗：${ex.message}"
            } finally { measuring = false }
        }
    }
    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) runMeasurement() else measurement = "未授予麥克風權限，無法回錄。"
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !loading) scope.launch {
            message = "檢查匯入音色…"
            try {
                val item = withContext(Dispatchers.IO) {
                    library.existingFor(uri) ?: run {
                        val file = SoundFontImporter.extract(context, uri)
                        library.register(uri, file)
                    }
                }
                activate(item)
            } catch (ex: Exception) {
                message = "匯入失敗：${ex.message}"
            }
        }
    }

    DisposableEffect(audioManager) {
        scanAudioOutputs()
        val deviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                if (addedDevices.any { it.isSink }) {
                    scanAudioOutputs()
                    routeRevision++
                }
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                if (removedDevices.any { it.isSink }) {
                    scanAudioOutputs()
                    routeRevision++
                }
            }
        }
        audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
        onDispose { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
    }
    DisposableEffect(Unit) {
        val ready = NativeEngine.start()
        NativeEngine.setGain(gain)
        val input = MidiInput(context, { midiStatus = it }, looper::onMidi)
        if (ready) scope.launch {
            try {
                withContext(Dispatchers.IO) { loadBundledFont(context) }
                fonts.clear()
                fonts.addAll(withContext(Dispatchers.IO) { library.all() })
                val preferred = fonts.firstOrNull { it.id == library.preferredId() }
                    ?: fonts.firstOrNull()
                if (preferred != null) activate(preferred)
            } catch (ex: Exception) { message = "啟動失敗：${ex.message}" }
        } else message = "無法開啟低延遲音訊輸出"
        onDispose {
            keyboard.allNotesOff()
            input.close()
            looper.close()
            NativeEngine.stop()
        }
    }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(500)
            audioStatus = NativeEngine.status()
            if (NativeEngine.consumeReconnectRequest() && !restarting) routeRevision++
            withContext(Dispatchers.IO) { NativeEngine.collectRetired() }
        }
    }
    LaunchedEffect(routeRevision) {
        if (routeRevision > 0) {
            delay(500) // Collapse short USB / 3.5mm connect/disconnect bursts.
            reconnectAudio()
        }
    }
    val selectedFont = fonts.firstOrNull { it.id == selectedFontId }
    val selectedTrack = loopState.tracks.firstOrNull { it.id == loopState.selectedId }
    val selectedPreset = selectedTrack?.let { track ->
        presets.firstOrNull { it.bank == track.bank && it.program == track.program }
    } ?: presets.getOrNull(presetIndex)
    var pendingTrackRemoval by remember { mutableStateOf<Int?>(null) }

    pendingTrackRemoval?.let { trackId ->
        AlertDialog(
            onDismissRequest = { pendingTrackRemoval = null },
            title = { Text("刪除樂器軌？") },
            text = { Text("將刪除此軌已錄製的 MIDI 音符。") },
            confirmButton = {
                TextButton(onClick = {
                    looper.removeTrack(trackId)
                    pendingTrackRemoval = null
                }) { Text("刪除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingTrackRemoval = null }) { Text("取消") }
            }
        )
    }

    deleteCandidate?.let { candidate ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("刪除音色庫？") },
            text = { Text("將刪除 ${candidate.title} 的 App 內副本（約 ${candidate.sizeMiB} MiB），不會刪除下載資料夾的原檔。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteCandidate = null
                    scope.launch {
                        if (selectedFontId == candidate.id) {
                            val gm = fonts.firstOrNull { it.isBundled }
                            if (gm != null) activate(gm)
                        }
                        val ok = withContext(Dispatchers.IO) { library.remove(candidate) }
                        fonts.clear()
                        fonts.addAll(withContext(Dispatchers.IO) { library.all() })
                        message = if (ok) "已刪除 ${candidate.title}" else "刪除失敗，請重新嘗試"
                    }
                }) { Text("刪除") }
            },
            dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text("取消") } }
        )
    }


    if (showDemoConfirmation) {
        AlertDialog(
            onDismissRequest = { showDemoConfirmation = false },
            title = { Text("載入兩小節示範？") },
            text = { Text("會取代目前所有樂器軌及錄音。") },
            confirmButton = { TextButton(onClick = {
                looper.loadDemo()
                showDemoConfirmation = false
            }) { Text("載入") } },
            dismissButton = { TextButton(onClick = {
                showDemoConfirmation = false
            }) { Text("取消") } }
        )
    }

    if (showInstrumentPicker) {
        val track = loopState.tracks.firstOrNull { it.id == loopState.selectedId }
        if (track == null || track.channel == 9) {
            LaunchedEffect(track?.id) { showInstrumentPicker = false }
        } else {
            var family by remember(track.id) {
                mutableIntStateOf(GmCategories.family(track.program))
            }
            val familyPresets = presets.filter { GmCategories.family(it.program) == family }
            val familyDefault = settings.getInt(
                "lastGMFamily:$family", GmCategories.defaults[family]
            )
            val recommended = familyPresets.firstOrNull {
                it.bank == 0 && it.program == familyDefault
            } ?: familyPresets.firstOrNull()
            AlertDialog(
                onDismissRequest = { showInstrumentPicker = false },
                title = { Text("選擇樂器", style = MaterialTheme.typography.titleLarge) },
                text = {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            GmCategories.names.forEachIndexed { index, name ->
                                FilterChip(selected = family == index, onClick = { family = index },
                                    label = { Text(name, maxLines = 1) })
                            }
                        }
                        if (recommended != null) {
                            TextButton(onClick = {
                                looper.setPreset(recommended.bank, recommended.program)
                                settings.edit().putInt("lastGMFamily:$family", recommended.program).apply()
                                showInstrumentPicker = false
                            }) { Text("★ 上次使用／預設：${recommended.displayName}", maxLines = 2) }
                        }
                        LazyColumn(modifier = Modifier.heightIn(max = 310.dp)) {
                            items(familyPresets) { preset ->
                                TextButton(
                                    onClick = {
                                        looper.setPreset(preset.bank, preset.program)
                                        settings.edit().putInt(
                                            "lastGMFamily:$family", preset.program
                                        ).apply()
                                        showInstrumentPicker = false
                                    }, modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(preset.displayName, modifier = Modifier.fillMaxWidth(),
                                        maxLines = 2)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showInstrumentPicker = false }) { Text("關閉") }
                }
            )
        }
    }

    if (showSettings) {
        Column(
            modifier = Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { showSettings = false }) { Text("‹ 演奏") }
                Text("設定", style = MaterialTheme.typography.titleLarge)
            }
            Text("速度 / 節拍", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("${loopState.bpm} BPM")
                Slider(loopState.bpm.toFloat(), { looper.setBpm(it.toInt()) },
                    modifier = Modifier.weight(1f), valueRange = 40f..240f)
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("循環小節")
                listOf(1, 2, 4, 8).forEach { bars ->
                    FilterChip(selected = loopState.bars == bars,
                        onClick = { looper.setBars(bars) }, label = { Text("$bars") })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("節拍器")
                Switch(checked = loopState.metronome, onCheckedChange = looper::setMetronome)
            }
            HorizontalDivider()
            Text("音色庫（SoundFont）", style = MaterialTheme.typography.titleMedium)
            ExposedDropdownMenuBox(expanded = showFonts, onExpandedChange = { showFonts = it && !loading }) {
                OutlinedTextField(
                    value = selectedFont?.let { "${it.title} · ${it.sizeMiB} MiB" } ?: "尚未載入",
                    onValueChange = {}, readOnly = true, singleLine = true,
                    label = { Text("目前音色庫") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(showFonts) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(expanded = showFonts, onDismissRequest = { showFonts = false }) {
                    fonts.forEach { font ->
                        DropdownMenuItem(
                            text = { Text("${font.title} · ${font.sizeMiB} MiB") },
                            onClick = {
                                showFonts = false
                                if (font.id != selectedFontId) scope.launch { activate(font) }
                            }
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !loading, onClick = { picker.launch(arrayOf("*/*")) }) {
                    Text("匯入 SF2 / ZIP / XZ")
                }
                if (selectedFont != null && !selectedFont.isBundled)
                    OutlinedButton(enabled = !loading, onClick = { deleteCandidate = selectedFont }) {
                        Text("刪除音色")
                    }
            }
            ExposedDropdownMenuBox(expanded = showPresets,
                onExpandedChange = { showPresets = it && !loading && selectedTrack?.channel != 9 }) {
                OutlinedTextField(
                    value = if (selectedTrack?.channel == 9) "鼓組（GM MIDI Ch 10）"
                        else selectedPreset?.displayName ?: "找不到可用樂器預設",
                    onValueChange = {}, readOnly = true, singleLine = true,
                    label = { Text("目前音色庫的樂器／Preset") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(showPresets) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(expanded = showPresets, onDismissRequest = { showPresets = false }) {
                    presets.forEachIndexed { index, preset ->
                        DropdownMenuItem(
                            text = { Text(preset.displayName) },
                            onClick = {
                                presetIndex = index
                                looper.setPreset(preset.bank, preset.program)
                                settings.edit()
                                    .putInt("presetBank:" + selectedFontId, preset.bank)
                                    .putInt("presetProgram:" + selectedFontId, preset.program)
                                    .apply()
                                showPresets = false
                            }
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    baseNote = (baseNote - 12).coerceAtLeast(0)
                    settings.edit().putInt("baseNote", baseNote).apply()
                }) { Text("− 八度") }
                OutlinedButton(onClick = {
                    baseNote = (baseNote + 12).coerceAtMost(96)
                    settings.edit().putInt("baseNote", baseNote).apply()
                }) { Text("+ 八度") }
                Text("C${baseNote / 12 - 1}", modifier = Modifier.padding(top = 13.dp))
            }
            Text("觸控力度：${velocity.toInt()}")
            Slider(value = velocity, onValueChange = {
                velocity = it
                settings.edit().putFloat("velocity", it).apply()
            }, valueRange = 1f..127f)
            Text("音量：${(gain * 100).toInt()}%")
            Slider(value = gain, onValueChange = {
                gain = it
                NativeEngine.setGain(it)
                settings.edit().putFloat("gain", it).apply()
            }, valueRange = 0.1f..1.5f)
            Text("音訊輸出", style = MaterialTheme.typography.titleMedium)
            ExposedDropdownMenuBox(expanded = showOutputs, onExpandedChange = { showOutputs = it && !restarting }) {
                OutlinedTextField(
                    value = if (requestedOutputId == 0) "自動跟隨系統" else
                        outputs.firstOrNull { it.id == requestedOutputId }?.let(::audioOutputName) ?: "自動跟隨系統",
                    onValueChange = {}, readOnly = true, singleLine = true,
                    label = { Text("輸出裝置") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(showOutputs) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(expanded = showOutputs, onDismissRequest = { showOutputs = false }) {
                    DropdownMenuItem(text = { Text("自動跟隨系統") }, onClick = {
                        showOutputs = false
                        requestedOutputId = 0
                        routeRevision++
                    })
                    outputs.forEach { output ->
                        DropdownMenuItem(text = { Text(audioOutputName(output)) }, onClick = {
                            showOutputs = false
                            requestedOutputId = output.id
                            routeRevision++
                        })
                    }
                }
            }
            OutlinedButton(onClick = { scanAudioOutputs(); routeRevision++ }, enabled = !restarting) {
                Text(if (restarting) "重新連線中…" else "重新連接音訊")
            }
    
            Text("音訊診斷", style = MaterialTheme.typography.titleMedium)
            Text(audioStatus, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { NativeEngine.midi(NativeEngine.TEST_PULSE, 0, 0, 0) }) { Text("播放測試音") }
                OutlinedButton(onClick = { NativeEngine.midi(NativeEngine.ALL_SOUNDS_OFF, 0, 0, 0) }) {
                    Text("全部停止")
                }
            }
            Text("麥克風回錄延遲（非 MIDI 按鍵延遲）", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = testCount == 3, onClick = { testCount = 3 },
                    enabled = !measuring, label = { Text("3 次") })
                FilterChip(selected = testCount == 5, onClick = { testCount = 5 },
                    enabled = !measuring, label = { Text("5 次") })
            }
            Text(measurement, style = MaterialTheme.typography.bodySmall)
            Button(enabled = !measuring && !loading, onClick = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED) runMeasurement()
                else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            }) { Text(if (measuring) "量測中…" else "開始多次回錄量測") }
            val version = remember {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
            }
            Text("LivePerformer v$version", style = MaterialTheme.typography.labelSmall)
        }
    } else {
        Column(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(40.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("LivePerformer", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (loading || restarting)
                    Text("載入中…", style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { showSettings = true }) {
                    Text("⚙ 設定")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().height(42.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { looper.setBpm(loopState.bpm - 1) }) { Text("−") }
                    Text("${loopState.bpm}", fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge)
                    Text(" BPM", style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { looper.setBpm(loopState.bpm + 1) }) { Text("+") }
                }
                Box {
                    TextButton(onClick = { showBarsMenu = true }) {
                        Text("${loopState.bars} 小節 ⌄")
                    }
                    DropdownMenu(expanded = showBarsMenu,
                        onDismissRequest = { showBarsMenu = false }) {
                        listOf(1, 2, 4, 8).forEach { count ->
                            DropdownMenuItem(
                                text = { Text("$count 小節") },
                                onClick = {
                                    looper.setBars(count)
                                    showBarsMenu = false
                                }
                            )
                        }
                    }
                }
                TextButton(onClick = { looper.setMetronome(!loopState.metronome) }) {
                    Text(if (loopState.metronome) "♩" else "♩̸")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("音量", style = MaterialTheme.typography.labelSmall)
                Slider(value = gain, onValueChange = {
                    gain = it
                    NativeEngine.setGain(it)
                    settings.edit().putFloat("gain", it).apply()
                }, modifier = Modifier.weight(1f).height(37.dp), valueRange = 0.1f..1.5f)
            }
            Text(
                when {
                    loopState.recording -> "● 錄製中"
                    loopState.armed -> "● 等待下一輪錄製"
                    loopState.playing -> "▶ 播放中"
                    else -> "Ⅱ 暫停"
                },
                color = if (loopState.recording) Color(0xFFF87171)
                    else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelSmall
            )
            LoopTimeline(loopState, modifier = Modifier.fillMaxWidth().height(50.dp))
            Row(
                modifier = Modifier.fillMaxWidth().height(55.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(onClick = { looper.playPause() }) {
                    Text(if (loopState.playing) "Ⅱ" else "▶", fontSize = 21.sp)
                }
                Button(
                    onClick = { looper.recordPause() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (loopState.recording || loopState.armed)
                            Color(0xFFCC333D) else Color(0xFF8E2C38)
                    )
                ) {
                    Text(if (loopState.recording || loopState.armed) "■" else "●",
                        fontSize = 21.sp)
                }
                OutlinedButton(onClick = { looper.clearSelected() }) { Text("清除") }
                OutlinedButton(onClick = { showDemoConfirmation = true }) { Text("示範") }
            }
            Row(
                modifier = Modifier.fillMaxWidth().height(36.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("樂器軌", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    if (!looper.addTrack()) message = "樂器軌已滿"
                }) { Text("＋ 樂器") }
                TextButton(onClick = {
                    if (!looper.addTrack(drums = true)) message = "鼓組軌已存在"
                }) { Text("＋ 鼓") }
            }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(loopState.tracks, key = { it.id }) { track ->
                    val selected = track.id == loopState.selectedId
                    val instrumentName = if (track.channel == 9) "鼓組"
                        else GmPrograms.names.getOrNull(track.program) ?: "樂器"
                    val family = if (track.channel == 9) "鼓"
                        else GmCategories.names[GmCategories.family(track.program)]
                    Surface(
                        modifier = Modifier.fillMaxWidth().height(51.dp)
                            .clickable { looper.selectTrack(track.id) },
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Box(
                                Modifier.width(4.dp).height(34.dp)
                                    .background(trackAccent(track.channel), RoundedCornerShape(3.dp))
                            )
                            Column(modifier = Modifier.width(57.dp)) {
                                Text(track.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                                Text(family, style = MaterialTheme.typography.labelSmall)
                            }
                            if (track.channel != 9) {
                                TextButton(
                                    modifier = Modifier.width(76.dp),
                                    onClick = {
                                        looper.selectTrack(track.id)
                                        showInstrumentPicker = true
                                    }, contentPadding = PaddingValues(horizontal = 1.dp)
                                ) {
                                    Text(instrumentName, maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelSmall)
                                }
                            } else {
                                Text("GM", style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.width(76.dp))
                            }
                            TrackPattern(track, Modifier.weight(1f).height(32.dp))
                            Box {
                                TextButton(
                                    onClick = { openTrackMenu = track.id },
                                    contentPadding = PaddingValues(0.dp),
                                    modifier = Modifier.width(31.dp)
                                ) { Text("⋮") }
                                DropdownMenu(
                                    expanded = openTrackMenu == track.id,
                                    onDismissRequest = { openTrackMenu = null }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(if (track.muted) "取消靜音" else "靜音") },
                                        onClick = { looper.muteTrack(track.id); openTrackMenu = null }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("刪除樂器軌") },
                                        enabled = loopState.tracks.size > 1,
                                        onClick = {
                                            pendingTrackRemoval = track.id
                                            openTrackMenu = null
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().height(32.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("鍵盤", style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    baseNote = (baseNote - 12).coerceAtLeast(0)
                    settings.edit().putInt("baseNote", baseNote).apply()
                }) { Text("−") }
                Text("C${baseNote / 12 - 1}", style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = {
                    baseNote = (baseNote + 12).coerceAtMost(96)
                    settings.edit().putInt("baseNote", baseNote).apply()
                }) { Text("+") }
            }
            AndroidView(
                factory = { keyboard },
                update = { view ->
                    view.baseNote = baseNote
                    view.velocity = velocity.toInt()
                },
                modifier = Modifier.fillMaxWidth().height(172.dp)
            )
        }
    }

}

