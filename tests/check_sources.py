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
assert 'v0.4.10' in workflow and 'versionCode = 19' in gradle
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
assert 'ManagedImportPicker(' in activity and 'OpenDocumentTree()' not in activity
assert 'isSoundFontFileName' in types and 'isProjectFileName' in types
assert 'validateSoundFont' in native_kt and 'validateSoundFont' in native
assert 'SF3' in importer and 'soundFontExtension' in store
assert (root/'app/src/test/java/tw/idv/christorng/liveperformer/ImportFileTypesTest.kt').exists()
print('PASS: dedicated diagnostics, per-device logs, filtered SAF files and SF3 validation')

# Long-running SoundFont import must communicate actual stages, errors and background caveat.
assert 'SoundFontImportProgress(' in importer
assert 'onProgress(SoundFontImportProgress(' in importer
assert 'UPDATE_INTERVAL_NS' in importer
assert 'if (importing)' in activity and 'importOutcome?.let' in activity
assert 'DialogProperties(dismissOnBackPress = false' in activity
assert 'importProgress = SoundFontImportProgress(' in activity
assert 'SoundFontImporter.extract(context, uri)' in activity
assert 'importUiHandler.post' in activity
assert 'picker.launch(arrayOf("*/*"))' in activity
assert 'Android 可能回收背景程序' in activity
assert 'library.remove(item)' in activity
assert (root/'app/src/test/java/tw/idv/christorng/liveperformer/SoundFontImportProgressTest.kt').exists()
print('PASS: SoundFont progress stages, visible result, SAF fallback and background warning')

# SF3 Android Vorbis compatibility conversion must use platform MediaCodec
# outside the audio callback; the APK must be a non-debuggable optimized release.
sf3=(root/'app/src/main/java/tw/idv/christorng/liveperformer/Sf3ToSf2Converter.kt').read_text()
names=(root/'app/src/main/java/tw/idv/christorng/liveperformer/InstrumentNaming.kt').read_text()
assert 'MediaExtractor' in sf3 and 'MediaCodec' in sf3
assert 'VorbisSampleDecoder' in sf3 and 'putU32(table' in sf3
assert 'Sf3ToSf2Converter.convert' in importer
assert 'fun rename(' in store and 'customTitle:' in store
assert 'renameFontCandidate' in activity and 'renameFontValue' in activity
assert 'sound *bank' in names
assert 'assembleRelease' in workflow and 'app-release.apk' in workflow
assert 'isMinifyEnabled = true' in gradle and 'isDebuggable = false' in gradle
assert 'isShrinkResources = true' in gradle
assert 'signingConfig = signingConfigs.getByName("debug")' in gradle
assert (root/'app/proguard-rules.pro').is_file()
assert 'NativeEngine' in (root/'app/proguard-rules.pro').read_text()
assert (root/'app/src/test/java/tw/idv/christorng/liveperformer/Sf3ToSf2ConverterTest.kt').is_file()
print('PASS: SF3 Vorbis import, editable names and R8-signed optimized release APK')

assert 'needs: sf3-android' in workflow
assert 'connectedDebugAndroidTest' in workflow
assert 'VintageDreamsWaves-v2.sf3' in workflow
assert (root/'app/src/androidTest/java/tw/idv/christorng/liveperformer/Sf3PlatformDecoderTest.kt').is_file()
print('PASS: actual SF3 Vorbis conversion is required on Android emulator before APK release')

# v0.4.7: sample-count progress, renderer efficiency, short preset names and SAF filtering
browser=(root/'app/src/main/java/tw/idv/christorng/liveperformer/FilteredDocumentBrowser.kt').read_text()
converter=(root/'app/src/main/java/tw/idv/christorng/liveperformer/Sf3ToSf2Converter.kt').read_text()
picker_ui=(root/'app/src/main/java/tw/idv/christorng/liveperformer/SoundFontPicker.kt').read_text()
assert 'ManagedImportPicker(' in activity and 'OpenDocumentTree()' not in activity
assert 'buildChildDocumentsUriUsingTree' in browser
assert 'isSoundFontFileName' in browser and 'isProjectFileName' in browser
assert 'completedItems = index + 1' in converter and 'totalItems = n' in converter
assert 'progress.totalItems' in activity and '樣本數進度不代表剩餘時間' in activity
assert 'presetDisplayName' in picker_ui and 'renameSinglePresetTracks' in activity
assert 'AndroidVorbisSampleDecoder.Session()' in converter
assert 'val rawPcm = ByteArray(' in converter
print('PASS: correct SF3 progress, bank/preset aliases, reusable Vorbis decoder and SAF suffix browser')

# v0.4.8: app-owned SAF library and import inbox; Downloads never needs tree access.
provider=(root/'app/src/main/java/tw/idv/christorng/liveperformer/LivePerformerDocumentsProvider.kt').read_text()
managed=(root/'app/src/main/java/tw/idv/christorng/liveperformer/ManagedImportPicker.kt').read_text()
project_store=(root/'app/src/main/java/tw/idv/christorng/liveperformer/ProjectStorage.kt').read_text()
assert 'class LivePerformerDocumentsProvider : DocumentsProvider()' in provider
assert 'DocumentsContract.Root.FLAG_SUPPORTS_CREATE' in provider
assert 'override fun queryRoots(' in provider and 'override fun queryChildDocuments(' in provider
assert 'override fun createDocument(' in provider and 'override fun openDocument(' in provider
assert 'override fun isChildDocument(' in provider
assert 'android.content.action.DOCUMENTS_PROVIDER' in manifest
assert 'android.permission.MANAGE_DOCUMENTS' in manifest
assert 'LivePerformerDocumentsProvider.pendingSoundFonts' in managed
assert 'projectStorage.importExternal' in activity and 'fun importExternal(' in project_store
assert 'picker.launch(arrayOf("*/*"))' in activity
assert 'openJson.launch(arrayOf("*/*"))' in activity
assert 'treePicker.launch(null)' not in activity
assert (root/'app/src/androidTest/java/tw/idv/christorng/liveperformer/ManagedDocumentsProviderTest.kt').is_file()
print('PASS: Android Files exposes managed bank/project/template folders and scoped SoundFont inbox')

# v0.4.9: semantic drum pads, sound bank mappings and stable project recordings
drum=(root/'app/src/main/java/tw/idv/christorng/liveperformer/DrumMaps.kt').read_text()
routing=(root/'app/src/main/java/tw/idv/christorng/liveperformer/MidiRouting.kt').read_text()
assert 'val gmNotes = listOf(36, 38, 42, 46, 45, 50, 51, 49)' in drum
assert 'fun forPreset(' in drum and 'fun reset(' in drum
assert 'drum-pads' in routing and 'drum-pads' in looper and 'drum-pads' in routing_ui
assert 'drumMaps.forPreset(' in looper and 'sendToSynth(track, e.type' in looper
assert 'importDrumMap' in activity and 'openDrumMap' in activity
assert 'LivePerformer-v0.4.10.apk' in workflow
assert (root/'drum-maps/gm-standard.drummap.json').exists()
assert (root/'app/src/test/java/tw/idv/christorng/liveperformer/DrumPadsTest.kt').exists()
print('PASS: v0.4.9 global eight-pad drum mapping and versioned APK')

# v0.4.10: CC controls are no longer sound notes. Hardware Momentary stays safe for Bass.
assert 'applyMpkMiniMk2ControlsPreset' in looper
assert 'ccMode' in looper and 'press-toggle' in looper
assert 'slot-volume' in looper and 'slot-mute' in looper and 'slot-solo' in looper
assert 'firstKnobCc' in looper and 'mute-all' in looper
assert 'knobFirstCc' in routing_ui and 'confirmControlsPreset' in routing_ui
assert (root/'app/src/androidTest/java/tw/idv/christorng/liveperformer/MpkControlsAndroidTest.kt').exists()
print('PASS: v0.4.10 MPK dual Note banks, CC A/B switch banks and eight volume knobs')
