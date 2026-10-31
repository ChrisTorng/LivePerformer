package tw.idv.christorng.liveperformer

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.os.Build
import androidx.core.view.WindowCompat
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PerformerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val library = remember(context) { SoundFontLibrary(context) }
    val keyboard = remember { PianoKeyboardView(context) }
    val fonts = remember { mutableStateListOf<SoundFontItem>() }
    val presets = remember { mutableStateListOf<InstrumentPreset>() }
    var selectedFontId by remember { mutableStateOf("gm") }
    var presetIndex by remember { mutableIntStateOf(0) }
    var showFonts by remember { mutableStateOf(false) }
    var showPresets by remember { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<SoundFontItem?>(null) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("啟動音訊中…") }
    var audioStatus by remember { mutableStateOf("Audio: starting…") }
    var midiStatus by remember { mutableStateOf("USB MIDI: scanning…") }
    var baseNote by remember { mutableIntStateOf(48) }
    var velocity by remember { mutableFloatStateOf(100f) }
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
                presetIndex = 0
                if (presets.isNotEmpty()) {
                    val p = presets.first()
                    NativeEngine.selectPreset(p.bank, p.program)
                }
                library.rememberSelected(item.id)
                message = "${item.title} 已切換 · ${presets.size} 個音色"
            }
        } catch (ex: Exception) {
            message = "切換音色庫失敗：${ex.message}"
        } finally {
            loading = false
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

    DisposableEffect(Unit) {
        val ready = NativeEngine.start()
        val input = MidiInput(context) { midiStatus = it }
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
            NativeEngine.stop()
        }
    }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(500)
            audioStatus = NativeEngine.status()
            withContext(Dispatchers.IO) { NativeEngine.collectRetired() }
        }
    }
    val selectedFont = fonts.firstOrNull { it.id == selectedFontId }
    val selectedPreset = presets.getOrNull(presetIndex)

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

    Column(
        modifier = Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("LivePerformer", style = MaterialTheme.typography.headlineMedium)
        val version = remember {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        }
        Text("低延遲鋼琴 · Android v$version", style = MaterialTheme.typography.labelMedium)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(midiStatus, style = MaterialTheme.typography.bodySmall)

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
        ExposedDropdownMenuBox(expanded = showPresets, onExpandedChange = { showPresets = it && !loading }) {
            OutlinedTextField(
                value = selectedPreset?.displayName ?: "找不到可用樂器預設",
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
                            NativeEngine.selectPreset(preset.bank, preset.program)
                            showPresets = false
                        }
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { baseNote = (baseNote - 12).coerceAtLeast(0) }) { Text("− 八度") }
            OutlinedButton(onClick = { baseNote = (baseNote + 12).coerceAtMost(96) }) { Text("+ 八度") }
            Text("C${baseNote / 12 - 1}", modifier = Modifier.padding(top = 13.dp))
        }
        Text("觸控 Velocity：${velocity.toInt()}")
        Slider(value = velocity, onValueChange = { velocity = it }, valueRange = 1f..127f)
        AndroidView(
            factory = { keyboard },
            update = { view -> view.baseNote = baseNote; view.velocity = velocity.toInt() },
            modifier = Modifier.fillMaxWidth().height(220.dp)
        )

        Text("音訊診斷", style = MaterialTheme.typography.titleMedium)
        Text(audioStatus, style = MaterialTheme.typography.bodySmall)
        Text("MIDI→callback 只代表排程時間，不是實際按鍵至耳機的延遲。", style = MaterialTheme.typography.bodySmall)
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
        Text("需用喇叭或有線回錄；回錄會改變音訊路徑，測試時的 xRun 不代表正常演奏狀態。",
            style = MaterialTheme.typography.bodySmall)
    }
}
