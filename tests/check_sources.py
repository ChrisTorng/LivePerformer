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
assert '播放測試音' in activity and '不會進行麥克風回錄' in activity
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
assert 'v0.1.2' in workflow and 'versionCode = 3' in gradle
print('PASS: displayed version, microphone measurement, ZIP/XZ import and stable signer wiring')
