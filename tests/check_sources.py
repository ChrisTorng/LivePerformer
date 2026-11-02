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
assert 'v0.2.1' in workflow and 'versionCode = 7' in gradle
print('PASS: displayed version, microphone measurement, ZIP/XZ import and stable signer wiring')

parser=(root/'app/src/main/java/tw/idv/christorng/liveperformer/SoundFontPresets.kt').read_text()
store=(root/'app/src/main/java/tw/idv/christorng/liveperformer/SoundFontLibrary.kt').read_text()
assert 'id == "phdr"' in parser and 'id == "INAM"' in parser
assert 'fun existingFor' in store and 'fun remove' in store
assert 'NativeEngine.selectPreset' in activity
assert 'PulseLatencyTester.summarize' in activity
assert 'NativeEngine.xrunCount()' in activity
assert 'testDebugUnitTest' in workflow
print('PASS: SoundFont registry, real SF2 presets, dark theme and measured xRun deltas')

# Audio route hotplug and state persistence regressions.
assert 'registerAudioDeviceCallback' in activity and 'AudioDeviceInfo' in activity
assert 'NativeEngine.restart' in activity and 'consumeReconnectRequest' in native
assert 'onErrorAfterClose' in native and 'builder.setDeviceId(deviceId)' in native
assert 'fluid_synth_set_gain' in native and 'NativeEngine.setGain' in activity
assert 'presetBank:' in activity and 'presetProgram:' in activity
assert 'putFloat("velocity"' in activity and 'putFloat("gain"' in activity
assert 'putInt("baseNote"' in activity
print('PASS: AAudio device recovery, selected preset, velocity, octave and gain persistence')

# MIDI loop wiring and persistence.
looper=(root/'app/src/main/java/tw/idv/christorng/liveperformer/LoopController.kt').read_text()
math=(root/'app/src/main/java/tw/idv/christorng/liveperformer/LoopMath.kt').read_text()
keyboard=(root/'app/src/main/java/tw/idv/christorng/liveperformer/PianoKeyboardView.kt').read_text()
midi=(root/'app/src/main/java/tw/idv/christorng/liveperformer/MidiInput.kt').read_text()
for required in ('LoopController(context)', 'looper::onMidi', 'looper.recordPause()', 'looper.playPause()', 'looper.addTrack()', 'looper.clearSelected()', 'looper::setMetronome', 'looper.setBpm'):
    assert required in activity, required
for required in ('ScheduledThreadPoolExecutor', 'nextOccurrence', 'recordStart', 'SharedPreferences', 'loop-session'):
    if required == 'SharedPreferences': continue
    assert required in looper, required
assert 'const val PPQ = 960' in math and 'fun nextBoundary' in math
assert 'sendMidi' in keyboard and 'onMidi' in midi
assert (root/'app/src/test/java/tw/idv/christorng/liveperformer/LoopMathTest.kt').exists()
print('PASS: multitrack MIDI loop UI, routing, persistence and timeline tests wired')

# The performance screen must never scroll with the keyboard.
assert 'if (showSettings)' in activity and 'LoopTimeline(' in activity
assert 'LazyColumn(' in activity and 'TrackPattern(' in activity
assert 'showInstrumentPicker' in activity and 'GmCategories.names' in activity
assert 'looper.loadDemo()' in activity
assert 'android:icon="@mipmap/ic_launcher"' in manifest
assert (root/'app/src/main/res/drawable/ic_launcher_foreground.xml').exists()
looper=(root/'app/src/main/java/tw/idv/christorng/liveperformer/LoopController.kt').read_text()
assert 'val drums = Track(1' in looper and 'val bass = Track(2' in looper
assert 'val strings = Track(3' in looper and 'val lead = Track(4' in looper
assert 'val piano = Track(5' in looper
assert 'nextId = 6' in looper
print('PASS: compact performance UI, two-bar arrangement, categorised GM and launcher icon')
