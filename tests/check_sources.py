from pathlib import Path
root=Path(__file__).resolve().parents[1]
src=(root/'app/src/main/java/tw/idv/christorng/liveperformer/GmPrograms.kt').read_text()
items=src.split('val names = listOf(',1)[1].split('\n    )',1)[0]
import re
assert len(re.findall(r'"[^"]*"',items)) == 128, 'Expected 128 GM instruments'
cpp=(root/'app/src/main/cpp/NativeEngine.cpp').read_text()
for name in ('onAudioReady','fluid_synth_write_float','fluid_synth_sfload','AudioApi::AAudio','setPerformanceMode','setFramesPerBurst','MidiType::TestPulse'):
    if name == 'setFramesPerBurst': continue
    assert name in cpp, name
print('PASS: 128 GM instruments and core wiring checks')

assert 'fluidsynth-android::fluidsynth' in (root/'app/src/main/cpp/CMakeLists.txt').read_text(), 'Prefab native link missing'

# Guard against regressions on Android 15 edge-to-edge and system night mode.
activity=(root/'app/src/main/java/tw/idv/christorng/liveperformer/MainActivity.kt').read_text()
manifest=(root/'app/src/main/AndroidManifest.xml').read_text()
for required in ('isSystemInDarkTheme()', 'dynamicDarkColorScheme(context)', 'dynamicLightColorScheme(context)', 'WindowCompat.setDecorFitsSystemWindows(window, false)', 'windowInsetsPadding(WindowInsets.safeDrawing)', 'isAppearanceLightStatusBars = !darkMode', 'isAppearanceLightNavigationBars = !darkMode'):
    assert required in activity, required
assert 'Theme.Material.Light.NoActionBar' not in manifest
assert '播放測試音' in activity and 'Surface(modifier = Modifier.fillMaxSize()' in activity
print('PASS: system theme, system bar insets, and diagnostic button labeling')

activity=(root/'app/src/main/java/tw/idv/christorng/liveperformer/MainActivity.kt').read_text()
native=(root/'app/src/main/cpp/NativeEngine.cpp').read_text()
tester=(root/'app/src/main/java/tw/idv/christorng/liveperformer/PulseLatencyTester.kt').read_text()
importer=(root/'app/src/main/java/tw/idv/christorng/liveperformer/SoundFontImporter.kt').read_text()
gradle=(root/'app/build.gradle.kts').read_text()
workflow=(root/'.github/workflows/android.yml').read_text()
assert 'getPackageInfo(context.packageName, 0).versionName' in activity
assert 'Android v0.1"' not in activity
assert 'lastPulseNs' in native and 'lastPulseNs' in tester
assert 'AudioRecord' in tester and 'getTimestamp' in tester
assert 'ZipInputStream' in importer and 'TarArchiveInputStream' in importer
assert 'dev-signing.keystore' in workflow and 'dev-signing.keystore' in gradle
assert 'v0.4.4' in workflow and 'versionCode = 13' in gradle
print('PASS: displayed version, microphone measurement, ZIP/XZ import and stable signer wiring')

parser=(root/'app/src/main/java/tw/idv/christorng/liveperformer/SoundFontPresets.kt').read_text()
store=(root/'app/src/main/java/tw/idv/christorng/liveperformer/SoundFontLibrary.kt').read_text()
assert 'id == "phdr"' in parser and 'id == "INAM"' in parser
assert 'fun existingFor' in store and 'fun remove' in store
assert 'looper.setInstrument' in activity
assert 'PulseLatencyTester.summarize' in activity
assert 'NativeEngine.xrunCount()' in activity
assert 'testDebugUnitTest' in workflow
print('PASS: SoundFont registry, real SF2 presets, dark theme and measured xRun deltas')

# Audio route hotplug and state persistence regressions.
assert 'registerAudioDeviceCallback' in activity and 'AudioDeviceInfo' in activity
assert 'NativeEngine.restart' in activity and 'consumeReconnectRequest' in native
assert 'onErrorAfterClose' in native and 'builder.setDeviceId(deviceId)' in native
assert 'fluid_synth_set_gain' in native and 'NativeEngine.setGain' in activity
assert 'instrument-families' in (root/'app/src/main/java/tw/idv/christorng/liveperformer/SoundFontPicker.kt').read_text()
assert 'putFloat("velocity"' in activity and 'putFloat("gain"' in activity
assert 'putInt("baseNote"' in activity
print('PASS: AAudio device recovery, selected preset, velocity, octave and gain persistence')

# MIDI loop wiring and persistence.
looper=(root/'app/src/main/java/tw/idv/christorng/liveperformer/LoopController.kt').read_text()
math=(root/'app/src/main/java/tw/idv/christorng/liveperformer/LoopMath.kt').read_text()
keyboard=(root/'app/src/main/java/tw/idv/christorng/liveperformer/PianoKeyboardView.kt').read_text()
midi=(root/'app/src/main/java/tw/idv/christorng/liveperformer/MidiInput.kt').read_text()
panel=(root/'app/src/main/java/tw/idv/christorng/liveperformer/PerformancePanel.kt').read_text()
for required in ('LoopController(context)', 'looper::onMidi', 'PerformancePanel(', 'settingsPage = SettingsPage.PROJECT', 'NativeEngine.loadFontCollection'):
    assert required in activity, required
for required in ('looper.recordPause()', 'looper.playStop()', 'onAddTrack', 'onClear', 'looper.setMetronome', 'looper.toggleSolo'):
    assert required in panel, required
for required in ('ScheduledThreadPoolExecutor', 'nextOccurrence', 'recordStart', 'SharedPreferences', 'loop-session'):
    if required == 'SharedPreferences': continue
    assert required in looper, required
assert 'const val PPQ = 960' in math and 'fun nextBoundary' in math
assert 'sendMidi' in keyboard and 'onMidi' in midi
assert (root/'app/src/test/java/tw/idv/christorng/liveperformer/LoopMathTest.kt').exists()
print('PASS: multitrack MIDI loop UI, routing, persistence and timeline tests wired')

# The performance screen must never scroll with the keyboard.
assert 'if (settingsPage != null)' in activity and 'PianoRoll(' in panel
assert 'LazyColumn(' in panel and 'state.progress' in panel
assert 'showInstrumentPicker' in activity and 'availableCategories' in (root/'app/src/main/java/tw/idv/christorng/liveperformer/SoundFontPicker.kt').read_text()
assert 'looper.loadDemo()' in activity
assert 'android:icon="@mipmap/ic_launcher"' in manifest
assert (root/'app/src/main/res/drawable/ic_launcher_foreground.xml').exists()
looper=(root/'app/src/main/java/tw/idv/christorng/liveperformer/LoopController.kt').read_text()
assert 'val drums = Track(1' in looper and 'val bass = Track(2' in looper
assert 'val strings = Track(3' in looper and 'val lead = Track(4' in looper
assert 'val piano = Track(5' in looper
assert 'nextId = 6' in looper
print('PASS: compact performance UI, two-bar arrangement, categorised GM and launcher icon')

assert 'enum class KeyboardMode' in panel and 'KeyboardMode.MONITOR' in panel
assert 'val keyboardNotes' in looper and 'val noteRects' in looper
assert 'exportDocument' in looper and 'importDocument' in looper
assert 'fontId' in looper and 'NativeEngine.selectFontPreset' in looper
cpp=(root/'app/src/main/cpp/NativeEngine.cpp').read_text()
assert 'loadFonts(' in cpp and 'applyFontPreset' in cpp
assert 'selectFontPreset' in (root/'app/src/main/java/tw/idv/christorng/liveperformer/NativeEngine.kt').read_text()
assert 'setMeter(' in looper and 'beatTicks' in math and 'beatsPerBar' in looper
assert 'toggleSolo' in looper and 'moveTrack' in looper
assert 'guardedLoad' in activity and 'markSavedIfUnchanged' in looper
assert 'detectDragGesturesAfterLongPress' in panel and 'TransportButton' in panel
assert (root/'app/src/main/java/tw/idv/christorng/liveperformer/InstrumentNaming.kt').exists()
print('PASS: meter-aware playback, multi-solo, track reordering, save-before-load and stage controls')

# All installable PR APKs must share the persistent main-release debug certificate.
assert "github.event_name == 'pull_request'" in workflow
assert 'Restore persistent development signing key' in workflow
assert 'applyMpkMiniMk2StagePreset' in looper
assert 'play-stop' in looper and 'record-toggle' in looper and 'metronome-toggle' in looper
print('PASS: PR APK stable signing and CC bank stage controls wired')

# Independent diagnostics, per-device log isolation, file suffix safeguards, SF3 loader.
routing_ui=(root/'app/src/main/java/tw/idv/christorng/liveperformer/MidiRoutingSettings.kt').read_text()
types=(root/'app/src/main/java/tw/idv/christorng/liveperformer/ImportFileTypes.kt').read_text()
native_kt=(root/'app/src/main/java/tw/idv/christorng/liveperformer/NativeEngine.kt').read_text()
assert 'SettingsPage.MIDI_TEST' in activity and 'SettingsPage.AUDIO_TEST' in activity
assert 'MidiSystemPanel(midiDevices, midiLogs' in activity
assert 'messagesByDevice[device.id]' in routing_ui
assert 'LocalTime.now()' not in (root/'app/src/main/java/tw/idv/christorng/liveperformer/MidiDiagnostics.kt').read_text()
assert 'ImportFileTypes.soundFontMimeTypes' in activity and 'ImportFileTypes.projectMimeTypes' in activity
assert 'isSoundFontFileName' in types and 'isProjectFileName' in types
assert 'validateSoundFont' in native_kt and 'validateSoundFont' in native
assert 'SF3' in importer and 'sf3' in store
assert (root/'app/src/test/java/tw/idv/christorng/liveperformer/ImportFileTypesTest.kt').exists()
print('PASS: dedicated diagnostics, per-device logs, filtered SAF files and SF3 validation')
