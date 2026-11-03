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
import androidx.activity.compose.BackHandler
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

private enum class SettingsPage { PROJECT, SYSTEM }

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
    var keyboardMode by remember {
        mutableStateOf(runCatching {
            KeyboardMode.valueOf(settings.getString("keyboardMode", "PLAY") ?: "PLAY")
        }.getOrDefault(KeyboardMode.PLAY))
    }
    var keyboardOverride by remember {
        mutableStateOf(settings.getBoolean("keyboardOverride", false))
    }
    var exportSettingsOnly by remember { mutableStateOf(false) }

    val presets = remember { mutableStateListOf<InstrumentPreset>() }
    var selectedFontId by remember { mutableStateOf("gm") }
    var presetIndex by remember { mutableIntStateOf(0) }
    var showFonts by remember { mutableStateOf(false) }
    var showPresets by remember { mutableStateOf(false) }
    var settingsPage by remember { mutableStateOf<SettingsPage?>(null) }
    var addingInstrument by remember { mutableStateOf(false) }
    var showLoadChoices by remember { mutableStateOf(false) }
    var pendingLoadAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var saveBeforeLoad by remember { mutableStateOf(false) }
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

    /** Rebuild the synth off the audio thread with every imported font loaded at once. */
    suspend fun activate(item: SoundFontItem? = null) {
        if (loading) return
        loading = true
        message = "載入音色庫…"
        try {
            val all = withContext(Dispatchers.IO) { library.all() }
            require(all.isNotEmpty()) { "找不到音色庫" }
            require(all.size <= 16) { "音色庫最多 16 套" }
            val ok = withContext(Dispatchers.IO) {
                NativeEngine.loadFontCollection(all.map { it.file.absolutePath }.toTypedArray())
            }
            if (!ok) {
                message = "載入失敗；保留之前音色庫"
            } else {
                fonts.clear()
                fonts.addAll(all)
                looper.configureFonts(all)
                if (item != null) {
                    looper.setFont(item.id)
                    selectedFontId = item.id
                }
                val current = all.firstOrNull { it.id == (item?.id ?: "gm") } ?: all.first()
                val info = withContext(Dispatchers.IO) {
                    SoundFontPresets.read(current.file)
                }
                presets.clear()
                presets.addAll(info.presets.ifEmpty {
                    if (current.isBundled) GmPrograms.names.mapIndexed { i, n ->
                        InstrumentPreset(0, i, n)
                    } else emptyList()
                })
                message = "音色庫已就緒（${all.size}）"
            }
        } catch (ex: Exception) {
            message = "音色庫載入失敗：${ex.message}"
        } finally { loading = false }
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
            val ready = withContext(Dispatchers.IO) { NativeEngine.restart(requestedOutputId) }
            if (!ready) {
                message = "音訊重新連線失敗，請選擇自動輸出或重新連接。"
                return
            }
            NativeEngine.setGain(gain)
            activate()
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
    fun guardedLoad(action: () -> Unit) {
        if (looper.needsSaveBeforeReplace()) pendingLoadAction = action else action()
    }

    val createJson = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) {
            saveBeforeLoad = false
        } else scope.launch {
            try {
                val content = looper.exportDocument(exportSettingsOnly)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                        it.write(content)
                    } ?: error("無法寫入檔案")
                }
                if (!exportSettingsOnly) looper.markSavedIfUnchanged(content)
                message = "已儲存"
                if (saveBeforeLoad && !looper.needsSaveBeforeReplace()) {
                    val resume = pendingLoadAction
                    pendingLoadAction = null
                    saveBeforeLoad = false
                    resume?.invoke()
                }
            } catch (e: Exception) {
                saveBeforeLoad = false
                message = "儲存失敗：${e.message}"
            }
        }
    }
    val openJson = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            try {
                val content = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                        it.readText()
                    } ?: error("無法開啟檔案")
                }
                looper.importDocument(content)
                val missing = looper.state.value.tracks.map { it.fontId }
                    .filter { id -> fonts.none { it.id == id } }.distinct()
                message = if (missing.isEmpty()) "已載入" else
                    "已載入；缺少音色庫 ${missing.joinToString()}（暫用 GM）"
            } catch (e: Exception) { message = "載入失敗：${e.message}" }
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
        val uiHandler = Handler(Looper.getMainLooper())
        val input = MidiInput(context, { text ->
            uiHandler.post {
                midiStatus = text
                if (!keyboardOverride) {
                    keyboardMode = if (text.contains("尚未連接")) KeyboardMode.PLAY
                        else KeyboardMode.MONITOR
                }
            }
        }, looper::onMidi)
        if (ready) scope.launch {
            try {
                withContext(Dispatchers.IO) { loadBundledFont(context) }
                fonts.clear()
                fonts.addAll(withContext(Dispatchers.IO) { library.all() })
                activate()
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
    LaunchedEffect(loopState.keyboardNotes, keyboardMode) {
        if (keyboardMode == KeyboardMode.MONITOR && loopState.keyboardNotes.isNotEmpty()) {
            val pressed = loopState.keyboardNotes.minOrNull() ?: baseNote
            if (pressed < baseNote || pressed > baseNote + 24) {
                baseNote = ((pressed / 12) * 12 - 12).coerceIn(0, 96)
            }
        }
    }
    val selectedTrack = loopState.tracks.firstOrNull { it.id == loopState.selectedId }
    val selectedFont = fonts.firstOrNull { it.id == selectedTrack?.fontId }
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
                        val ok = withContext(Dispatchers.IO) { library.remove(candidate) }
                        if (ok) {
                            looper.replaceFont(candidate.id)
                            activate()
                        }
                        message = if (ok) "已刪除 ${candidate.title}" else "刪除失敗，請重新嘗試"
                    }
                }) { Text("刪除") }
            },
            dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text("取消") } }
        )
    }


    BackHandler(enabled = settingsPage != null) { settingsPage = null }

    pendingLoadAction?.let {
        AlertDialog(
            onDismissRequest = { pendingLoadAction = null; saveBeforeLoad = false },
            title = { Text("專案尚未儲存") },
            text = { Text("目前的專案含有尚未儲存至檔案的內容。讀取其他專案或預設範本會覆蓋現有音軌。") },
            confirmButton = {
                TextButton(onClick = {
                    saveBeforeLoad = true
                    exportSettingsOnly = false
                    createJson.launch("LivePerformer-project.json")
                }) { Text("先儲存再讀取") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        val resume = pendingLoadAction
                        pendingLoadAction = null
                        saveBeforeLoad = false
                        resume?.invoke()
                    }) { Text("直接讀取") }
                    TextButton(onClick = {
                        pendingLoadAction = null
                        saveBeforeLoad = false
                    }) { Text("取消") }
                }
            }
        )
    }

    if (showInstrumentPicker && (addingInstrument || selectedTrack != null)) {
        SoundFontPicker(
            track = if (addingInstrument) null else selectedTrack,
            fonts = fonts,
            context = context,
            onChoose = { fontId, bank, program, name, category ->
                if (addingInstrument) {
                    if (!looper.addChosenTrack(fontId, bank, program, category, name))
                        message = if (category == "鼓組") "鼓組聲道已使用" else "已達一般樂器聲道上限"
                } else {
                    looper.setInstrument(fontId, bank, program, name, category)
                }
                selectedFontId = fontId
            },
            onClose = { showInstrumentPicker = false }
        )
    }

    if (settingsPage != null) {
        Column(
            modifier = Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { settingsPage = null }) { Text("‹ 演奏") }
                Text(if (settingsPage == SettingsPage.PROJECT) "專案設定" else "系統設定",
                    style = MaterialTheme.typography.titleLarge)
            }
            if (settingsPage == SettingsPage.PROJECT) {
            Text("速度 / 拍號 / 循環小節", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("${loopState.bpm} BPM")
                Slider(loopState.bpm.toFloat(), { looper.setBpm(it.toInt()) },
                    modifier = Modifier.weight(1f), valueRange = 40f..240f)
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("拍號")
                listOf(2 to 4, 3 to 4, 4 to 4, 6 to 8).forEach { (beats, unit) ->
                    FilterChip(selected = loopState.beatsPerBar == beats && loopState.beatUnit == unit,
                        onClick = { looper.setMeter(beats, unit) },
                        label = { Text("$beats/$unit") })
                }
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
            Text("專案", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = {
                    exportSettingsOnly = false
                    createJson.launch("LivePerformer-project.json")
                }) { Text("存專案") }
                Box {
                    OutlinedButton(onClick = { showLoadChoices = true }) { Text("讀取專案 / 預設") }
                    DropdownMenu(expanded = showLoadChoices,
                        onDismissRequest = { showLoadChoices = false }) {
                        DropdownMenuItem(text = { Text("讀取已儲存專案") },
                            onClick = {
                                showLoadChoices = false
                                guardedLoad {
                                    openJson.launch(arrayOf("application/json", "text/plain", "*/*"))
                                }
                            })
                        DropdownMenuItem(text = { Text("載入內建示範專案") },
                            onClick = {
                                showLoadChoices = false
                                guardedLoad { looper.loadDemo(); settingsPage = null }
                            })
                    }
                }
                OutlinedButton(onClick = {
                    exportSettingsOnly = true
                    createJson.launch("LivePerformer-settings.json")
                }) { Text("存音軌範本") }
            }
            Text("音軌編輯、樂器選擇及排序在演奏畫面操作。", style = MaterialTheme.typography.bodySmall)
            } else {
            Text("音色庫", style = MaterialTheme.typography.titleMedium)
            fonts.forEach { font ->
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(font.title, modifier = Modifier.weight(1f), maxLines = 1)
                    if (!font.isBundled) {
                        TextButton(onClick = { deleteCandidate = font }) { Text("刪除") }
                    }
                }
            }
            OutlinedButton(enabled = !loading, onClick = {
                picker.launch(arrayOf("*/*"))
            }) { Text("匯入 SF2 / ZIP / XZ") }
            OutlinedButton(onClick = {
                showInstrumentPicker = true
            }) { Text("目前音軌選樂器") }
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
        }
    } else {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            PerformancePanel(
                state = loopState, looper = looper, keyboard = keyboard,
                keyboardMode = keyboardMode,
                onKeyboardMode = {
                    keyboardMode = keyboardMode.next()
                    keyboardOverride = true
                    settings.edit().putBoolean("keyboardOverride", true)
                        .putString("keyboardMode", keyboardMode.name).apply()
                },
                baseNote = baseNote,
                onBaseNote = {
                    baseNote = it
                    settings.edit().putInt("baseNote", it).apply()
                },
                velocity = velocity.toInt(), volume = gain,
                onVolume = {
                    gain = it
                    NativeEngine.setGain(it)
                    settings.edit().putFloat("gain", it).apply()
                },
                onProjectSettings = { settingsPage = SettingsPage.PROJECT },
                onSystemSettings = { settingsPage = SettingsPage.SYSTEM },
                onInstrument = {
                    addingInstrument = false
                    showInstrumentPicker = true
                },
                onAddTrack = {
                    addingInstrument = true
                    showInstrumentPicker = true
                },
                onDelete = { id ->
                    val track = loopState.tracks.firstOrNull { it.id == id }
                    if (track?.notes == 0) looper.removeTrack(id)
                    else pendingTrackRemoval = id
                }
            )
        }
    }

}

