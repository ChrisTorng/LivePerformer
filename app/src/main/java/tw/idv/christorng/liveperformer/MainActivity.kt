package tw.idv.christorng.liveperformer

import android.os.Bundle
import android.os.Build
import androidx.core.view.WindowCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
            MaterialTheme(colorScheme = colors) { PerformerScreen() }
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
    var message by remember { mutableStateOf("Starting audio…") }
    var audioStatus by remember { mutableStateOf("Audio: starting…") }
    var midiStatus by remember { mutableStateOf("USB MIDI: scanning…") }
    var instrument by remember { mutableIntStateOf(0) }
    var showPrograms by remember { mutableStateOf(false) }
    var baseNote by remember { mutableIntStateOf(48) }
    var velocity by remember { mutableFloatStateOf(100f) }
    var currentBank by remember { mutableStateOf("GeneralUser GS") }
    val keyboard = remember { PianoKeyboardView(context) }
    // Keep file operations on a dedicated worker to avoid blocking the UI or audio callback.
    suspend fun loadFont(path: String, display: String) {
        message = "正在載入 $display…"
        val ok = withContext(Dispatchers.IO) { NativeEngine.loadSf2(path) }
        if (ok) {
            currentBank = display
            instrument = 0
            NativeEngine.program(0)
            message = "$display 已載入"
        } else message = "SoundFont 載入失敗；請檢查檔案／音訊引擎"
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            message = "正在匯入 SoundFont…"
            try {
                val file = withContext(Dispatchers.IO) { SoundFontImporter.import(context, uri) }
                loadFont(file.absolutePath, "匯入音色（" + file.name + "）")
            } catch (ex: Exception) { message = "匯入失敗：${ex.message}" }
        }
    }
    DisposableEffect(Unit) {
        val ok = NativeEngine.start()
        message = if (ok) "正在準備 GM 樂器庫…" else "Audio 啟動失敗：無法開啟低延遲輸出"
        val input = MidiInput(context) { midiStatus = it }
        if (ok) scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { loadBundledFont(context) }
                loadFont(file.absolutePath, "GeneralUser GS")
            } catch (ex: Exception) { message = "預設音色載入失敗：${ex.message}" }
        }
        onDispose {
            keyboard.allNotesOff()
            input.close()
            NativeEngine.stop()
            // Pending synth resources are reclaimed by the next app process stop or worker collection.
        }
    }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(500)
            audioStatus = NativeEngine.status()
            withContext(Dispatchers.IO) { NativeEngine.collectRetired() }
        }
    }
    Column(
        modifier = Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("LivePerformer", style = MaterialTheme.typography.headlineMedium)
        Text("低延遲鋼琴 · Android v0.1", style = MaterialTheme.typography.labelMedium)
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Text(midiStatus, style = MaterialTheme.typography.bodySmall)
        ExposedDropdownMenuBox(expanded = showPrograms, onExpandedChange = { showPrograms = it }) {
            OutlinedTextField(
                value = "${instrument + 1}. ${GmPrograms.names[instrument]}",
                onValueChange = {}, readOnly = true, singleLine = true,
                label = { Text("GM 樂器") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(showPrograms) },
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
            )
            ExposedDropdownMenu(expanded = showPrograms, onDismissRequest = { showPrograms = false }) {
                GmPrograms.names.forEachIndexed { index, name ->
                    DropdownMenuItem(
                        text = { Text("${index + 1}. $name") },
                        onClick = { instrument = index; NativeEngine.program(index); showPrograms = false }
                    )
                }
            }
        }
        Text("音色庫：$currentBank", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                scope.launch {
                    try {
                        val file = withContext(Dispatchers.IO) { loadBundledFont(context) }
                        loadFont(file.absolutePath, "GeneralUser GS")
                    } catch (ex: Exception) { message = "載入失敗：${ex.message}" }
                }
            }) { Text("預設 GM") }
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                Text("匯入 SF2 / ZIP / XZ")
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
        Text("MIDI→callback 是軟體內部排程時間，不等於耳機實際發聲延遲。", style = MaterialTheme.typography.bodySmall)
        Text("播放測試音只會發出短聲，不會進行麥克風回錄或量測實際延遲。", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { NativeEngine.midi(NativeEngine.TEST_PULSE, 0, 0, 0) }) { Text("播放測試音") }
            OutlinedButton(onClick = { NativeEngine.midi(NativeEngine.ALL_SOUNDS_OFF, 0, 0, 0) }) { Text("全部停止") }
        }
        Text("可匯入 Salamander Grand Piano SF2，比較 GM 與高品質鋼琴。錄製與 Loop 暫不提供。", style = MaterialTheme.typography.bodySmall)
    }
}
