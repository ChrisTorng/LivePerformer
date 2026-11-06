# LivePerformer — Android low-latency MIDI instrument and looper (v0.4.5)

**Status: early source prototype, not yet device-verified.** Initial target: Samsung Galaxy A25 5G, USB MIDI keyboard, wired 3.5 mm headphones. v0.2.1 adds an ergonomic single-screen interface and v0.2 includes an experimental MIDI-only loop recorder; physical device performance remains unverified.

## MVP

- Multitrack MIDI loop: play/pause, one-cycle recording/overdub, clear, tempo (40–240 BPM), 1/2/4/8 bars, metronome toggle, per-track mute, add/select/delete instruments. Default 120 BPM, two bars, and a one-bar drum pattern repeated twice. Track MIDI clips and settings persist locally. Playback uses a 4 ms Android scheduler and is **not sample-accurate**; audio synthesis remains native.
- Native Oboe / AAudio low-latency stereo output (try Exclusive, fall back to Shared).
- FluidSynth 2.4.6 SoundFont synthesis (SF2; SF3 only if the bundled Android library can decode it) via a directly linked Android Prefab AAR.
- Auto-discovery of USB MIDI devices, notes, velocity, pedal CC64, program change and pitch bend.
- 128 GM instrument menu, two-octave multitouch piano keyboard, octave shift, adjustable on-screen velocity.
- Automatic first-launch GeneralUser GS bank; imported SF2 supports Salamander Grand Piano.
- Diagnostics: actual audio sample rate, frames per burst, buffer size, xRuns, most recent MIDI-to-callback scheduling time and overrun counter, plus deterministic short burst trigger.

## Build (Windows + Android Studio)

1. Open this directory in **Android Studio**. Install Android SDK 37, Android NDK and CMake when prompted. Use JDK 17+.
2. **The Gradle wrapper JAR is not included in this source snapshot.** In Android Studio, configure Gradle 9.6.0 (or generate the wrapper using `gradle wrapper --gradle-version 9.6.0` from a local Gradle installation).
3. Sync and build the `app` module. The `prepareGmSoundFont` Gradle task downloads the 31 MiB GM bank from its original repository and verifies its SHA-256. To build offline, place the matching `GeneralUser-GS.sf2` in `app/src/main/assets/`.
4. Install the debug APK on the Galaxy A25 5G, use wired headphones, connect USB MIDI keyboard via a USB-C Host hub, and verify audio route + instrument loading.
5. For the high-quality piano, download the SF2 variant of Salamander Grand Piano from https://freepats.zenvoid.org/Piano/acoustic-grand-piano.html and select **匯入 SF2**.

## Important limitations / test status

- No Android SDK/NDK or network access to Maven artifacts is available in the current code-generation environment. **APK compilation and hardware latency have not been verified.** Do not treat this as a shipped APK.
- FluidSynth's Android AAR is provided by the third-party `net.volcanomobile.fluidsynth-android` project. The application links FluidSynth through Prefab so the required native library can be packaged and checked by Android CI; device testing remains necessary.
- MIDI is currently processed at the next Oboe callback boundary (buffer-quantized), not sample-accurate. The displayed MIDI→callback time is **not** the actual press-to-sound latency.
- The test burst only provides an output stimulus; acoustic/microphone loopback correlation and end-to-end hardware latency measurement have **not** yet been implemented.
- The app is arm64-v8a only for the first target. For Windows, reuse the native sound synthesis + event queue, but implement WASAPI/ASIO and MIDI adapters independently.
- The SoundFont and FluidSynth have separate licensing terms from this application's own source code (MIT).

## Testing

`bash tests/run_tests.sh` runs host-side bounded MIDI queue concurrency tests and checks the GM list. Additional Android compilation and physical hardware timing tests remain necessary.

## Next engineering priorities

- Native sample-accurate looper/clock for consistent groove and future Windows reuse; MIDI event timing under Android scheduling load.
- First device-verified APK, confirmation of FluidSynth AAR packaging, test Oboe duplex / AAudio stream behavior and xRun stability.
- Build an independent recorder / AudioRecord cross-correlation loopback test with calibrated timestamp/latency model.
- Sample-accurate MIDI scheduling with AAudio stream timestamps and edge-case MIDI parsing including split messages and system common.
- USB Audio routing and device-recovery tests; Samsung Galaxy A25 5G host+PD hub tests.

## Sources

- Oboe: https://github.com/google/oboe
- FluidSynth Android Maven: https://central.sonatype.com/artifact/net.volcanomobile.fluidsynth-android/fluidsynth-android
- GeneralUser GS: https://github.com/mrbumpy409/GeneralUser-GS
- Salamander SF2: https://freepats.zenvoid.org/Piano/acoustic-grand-piano.html

## GitHub Actions

[Android CI](https://github.com/ChrisTorng/LivePerformer/actions/workflows/android.yml) runs C++ queue tests, source checks and compiles an `arm64-v8a` debug APK on push and pull request. On success, download the `LivePerformer-debug-apk` artifact from the workflow run. Build success does **not** establish measured audio latency or device compatibility.

## Salamander SF2 import and packaging

The FreePats 296 MiB TAR.XZ is compressed. The extracted .sf2 is substantially larger (around 1.2 GB). The installer imports .sf2, .zip, or .tar.xz via the document picker and extracts one SF2 into app-private storage. Allow ample free storage and memory; real-device performance is not yet verified.

A GitHub Action can repackage the original archive as an ordinary ZIP: [Salamander ZIP workflow](https://github.com/ChrisTorng/LivePerformer/actions/workflows/salamander.yml).

## v0.2.1 performance workflow

- Main screen keeps transport, 2-bar beat progress, note-pattern track lanes and piano keys visible simultaneously; tracks scroll only inside their own bounded list. Tap instrument name for 16 GM family categories (remember last used per family).
- Settings screen contains SoundFont import, audio route, master controls and diagnostic recording.
- Factory demo is two *different* bars of drums (bar 2 fill), bass (C/A minor phrases), string chords and a lead melody. The piano track is empty and selected to practise. **示範** prompts before replacing existing projects, including those migrated from v0.2.
- Launcher now has a dedicated adaptive loop/note icon.

## v0.3 controls

- Landscape-first: ▶/Ⅱ, ●/■, clear, BPM and bar count on a compact row; white playhead moves across all pitch/length/velocity lanes.
- Tap a track to select; tap its label again to choose a General MIDI category, imported SoundFont, and preset.
- Keyboard button cycles playable / MIDI monitor / hidden. USB MIDI auto-selects monitor until the mode is manually overridden.
- Settings → 存專案 / 讀取 / 存設定. Project files contain MIDI clips; settings files do not, and reading settings preserves matching track clips.
- SoundFonts are device-local. Import matching SF2 files before opening a project that references them.
- Android soft scheduler remains best effort and requires real-device latency testing.


## v0.4.2 experimental multi-device MIDI routing

- System -> MIDI testing lists connected MIDI devices and their own 50-event logs (channel, note/CC/program/pitch bend; no repeated timestamps or source names). Clock (0xF8) and Active Sensing (0xFE) are excluded to avoid drowning out test data.
- Project -> MIDI routing stores up to 48 device/channel/kind/number-range/target/action rules under the `midiRoutes` JSON field. Note or momentary CC pads can play different tracks, optionally transposed or mapped to an eight-step major scale.
- The MPK mini MK2 shortcut uses the supplied Editor screenshot: channel 2, Note 36-43 to existing drum track, Note 44-51 to existing Bass track. Source keyboards default to playing the selected track; extra CC mappings can be manually added.
- Explicit CC rules may control track volume CC7, track pan CC10, or master FluidSynth gain; Program Change is diagnostic-only unless mapped to select-track.
- MIDI output Clock is selectable under System → MIDI testing and sends Start/Stop and MIDI Clock at 24 PPQN from the existing 4-ms scheduler. MPK mini MK2 must use Editor Clock External and Send To RAM for ARP external synchronization; clock timing is best-effort, not sample-accurate.
- Existing projects without `midiRoutes` remain supported. MIDI output ID is not persisted across reconnect. Two identical devices without distinguishable serial numbers share a mapping identity.
- Limitations: only one armed track records at a time; simultaneous live multi-track notes do not automatically record every played track. No MIDI Learn, custom drum map, minor scale, Windows GUI or physical device verification yet.

## v0.4.3: Mobile MIDI mapping refinement (feature branch)

- Narrow portrait screen: project buttons wrap, MIDI route editor occupies a separate settings page. Back returns to Project settings, then Performance.
- CC numbers (e.g. 20-27) are controller identifiers, not notes. Routes convert them to playable pitches without reprogramming the hardware.
- Per-route 12-key major/natural-minor scale with octave, or custom per-note mapping (maximum 16 notes, -1 disables a pad). Custom mappings and scale mode persist in .lvp/.lvpt JSON alongside the existing route fields.
- Existing projects without noteMap and scaleMode still import.
- Route editing replaces the old rule in place. Real Android MIDI hardware and portrait layouts still need device validation.
- NOTE: PR branch builds may be downloaded as GitHub Actions artifacts. The main-release workflow is still configured for the v0.4.2 release tag until its publishing configuration can be updated.

### PR APK signature and MPK mini MK2 CC Bank B controls

- CI restores the publicly shared disposable **development-only** keystore for both PR and main builds. Pull-request APKs can now replace an installed v0.4.2 development build without uninstalling the app and losing app-private projects or imported SF2 data. Never reuse this public key for production distribution.
- The optional **CC Bank B stage** preset expects MK2 Pad MIDI Channel 2: CC28-31 (Pads 1-4, bottom row) hardware **Toggle** map to mute/unmute Drums/Bass/Strings/Lead, and CC32-35 (Pads 5-8, top row) hardware **Momentary** map to Play/Stop, Record/End, Metronome toggle and Select Next Track.
- Toggle CC values >=64 set the assigned switch ON, <64 set it OFF; momentary commands execute on the ON press only (not release). Source Bank B Note mode also uses the same per-pad toggle/momentary settings, so Toggle Note pads are unsuitable for ordinary short Bass notes.
- This shortcut requires existing tracks identifiable as drums, bass, strings, lead, and will refuse to overwrite existing overlapping CC28-35 routes. The individual route editor also exposes Mute/Solo and transport commands for other MIDI controllers.
- Save the MK2 Editor program and **Send To RAM** / the desired numbered hardware program; an editor file alone does not change the MIDI keyboard. For external arpeggiator sync select MIDI Clock output under LivePerformer System → MIDI testing, set MK2 Arpeggiator CLOCK External, then press Play.
- Because controller latch state is separate from app/project state, a previously toggled pad can be out of sync after loading a new project. Toggle buttons set the received state explicitly rather than flipping state blindly; use the app UI to inspect current M/S state.

## v0.4.4: separate MIDI/audio diagnostics, SAF filtering, SF3 import

- System settings keeps everyday configuration; **MIDI 測試** and **音訊測試** open separate full-page screens. Back returns to System settings, then Performance. Existing project-specific MIDI routing remains under Project settings.
- The MIDI test screen groups incoming events in **one read-only scrollable text area per connected device**, at most 50 events per device. Entries show channel/note/CC/pitch/program information, without timestamps or repeated device names. Clock and Active Sensing are suppressed from event logs; MIDI Clock output selection is still in the MIDI test screen.
- Android SAF document selection uses specific MIME types rather than `*/*`. **MIME filtering depends on the document provider**: after selection, imported SoundFonts must have `.sf2`, `.sf3`, `.zip` or `.xz` suffixes (including `.tar.xz`), and external projects must have `.lvp` or `.lvpt` suffixes. Filename checks are authoritative even when a provider exposes unrelated octet-stream files.
- ZIP and TAR.XZ archives can contain SF2 or SF3. XZ-compressed single SoundFont files are accepted as well. Archives are extracted into private storage with a 2 GB expanded-file cap. Internal SF2/SF3 filenames are detected, not written as paths.
- SF3 uses the same RIFF/sfbk preset parser and FluidSynth loading path as SF2. **SF3 decoder support is not guaranteed in the existing Android AAR**: the importer probes a separate temporary native synth and *rejects/removes* an SF3 that the installed FluidSynth cannot decode, without corrupting the library. A real SF3 sample and device APK still require verification. SFZ is not supported and requires an additional sampler (such as sfizz).

## v0.4.5: Observable SoundFont imports and lifecycle warning

- Importing SF2/SF3/ZIP/XZ now displays a non-dismissable progress dialog with the current operation. Plain SF2/SF3 copying uses file-size-based percentage when the Android document provider reports a size; compressed archives show *extracted* MiB and an indeterminate progress indicator because the final decompressed size cannot be known in advance.
- Import stages include copying/unpacking, RIFF header verification, SF3 decoder probe, registry update and reloading the entire active FluidSynth collection. The last stage may still take significant time; it does **not** report a fabricated percentage.
- Success and failure are both displayed in a visible result dialog. If the new sound bank cannot be activated, its newly imported copy is removed to avoid poisoning subsequent startup; the original file remains unchanged.
- The Android Documents provider may label `.sf2` and `.sf3` files with unusual MIME types, hiding them from the filtered SAF picker. The **顯示所有檔案** fallback exposes them while application-side extension validation still rejects unrelated files.
- The import dialog warns about backgrounding: switching to another app does not intrinsically cancel the import while the current process survives, but Android can kill a background process. No background service or resumable import is implemented. Avoid switching apps or rotating the device while importing.
