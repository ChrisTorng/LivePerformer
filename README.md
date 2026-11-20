# LivePerformer — Android low-latency MIDI instrument and looper (v0.4.18)

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
- The MPK mini MK2 channel-2 Note 36–43 Pad Bank A is a fixed eight-role drum layout (GM 36/38/42/46/45/50/51/49), independent of the selected SoundFont. The Note Bank B (44–51) now defaults to Bass C2 major, adjustable via the existing route editor for key, major/minor mode, octave and each note; the formerly mixed CC Bank B controls are replaced by the new all-Toggle switch profile. Source keyboards default to playing the selected track; extra CC mappings can be manually added.
- Explicit CC rules may control track volume CC7, track pan CC10, or master FluidSynth gain; Program Change is diagnostic-only unless mapped to select-track.
- MIDI output Clock is selectable under System → MIDI testing and sends Start/Stop and MIDI Clock at 24 PPQN from the existing 4-ms scheduler. MPK mini MK2 must use Editor Clock External and Send To RAM for ARP external synchronization; clock timing is best-effort, not sample-accurate.
- Existing projects without `midiRoutes` remain supported. MIDI output ID is not persisted across reconnect. Two identical devices without distinguishable serial numbers share a mapping identity.
- Limitations: only one armed track records at a time; simultaneous live multi-track notes do not automatically record every played track. No MIDI Learn, Windows GUI or physical device verification yet. Non-GM hi-hat choking depends on the SoundFont exclusiveClass/release behavior; arbitrary samples cannot be guaranteed to choke.

## v0.4.3: Mobile MIDI mapping refinement (feature branch)

- Narrow portrait screen: project buttons wrap, MIDI route editor occupies a separate settings page. Back returns to Project settings, then Performance.
- CC numbers (e.g. 20-27) are controller identifiers, not notes. Routes convert them to playable pitches without reprogramming the hardware.
- Per-route 12-key major/natural-minor scale with octave, or custom per-note mapping (maximum 16 notes, -1 disables a pad). Custom mappings and scale mode persist in .lvp/.lvpt JSON alongside the existing route fields.
- Existing projects without noteMap and scaleMode still import.
- Route editing replaces the old rule in place. Real Android MIDI hardware and portrait layouts still need device validation.
- NOTE: PR branch builds may be downloaded as GitHub Actions artifacts. The main-release workflow is still configured for the v0.4.2 release tag until its publishing configuration can be updated.

### PR APK signature and MPK mini MK2 CC Bank B controls

- CI restores the publicly shared disposable **development-only** keystore for both PR and main builds. Pull-request APKs can now replace an installed v0.4.2 development build without uninstalling the app and losing app-private projects or imported SF2 data. Never reuse this public key for production distribution.
- The older **CC Bank B stage** example has been replaced in v0.4.11 by two independently configurable control-bank presets. Bank A CC20–27 uses hardware Momentary and software press-toggle, while Bank B CC28–35 uses physical Toggle and absolute CC state for track muting.
- Bank B physical Toggle CC values >=64 set the assigned switch ON, <64 set it OFF; Bank A Momentary CC commands toggle the app state when pressed (CC >=64), ignoring release. The user tested this MKII hardware and confirmed that CC latch configuration did not interfere with Note Bank B Bass note triggering.
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

## v0.4.6 — Android SF3 compatibility, SoundFont labels and optimized APK

- Root cause: the prebuilt `fluidsynth-android` AAR omits libsndfile, so its native SF3 Vorbis sample decoding does **not** work. Installing a random Google Play codec cannot add support to that native library.
- As an immediate in-app compatibility path, **SF3 files are converted once during import to an SF2 PCM file** using Android's built-in Vorbis MediaExtractor/MediaCodec (no third-party downloaded codec). The converter reconstructs the SoundFont RIFF structures, updates SF3 compressed-sample offsets/loops and strips the compression flag. The converted SF2 is validated by FluidSynth before registration; broken partial copies are deleted. This keeps real-time rendering unmodified but consumes considerably more storage during and after import; a large SF3 may expand to hundreds of MiB or more and cannot exceed the current 2 GB SoundFont limit. This is not equivalent to native compressed-SF3 loading and still needs physical-device testing.
- Imported SoundFont titles now use the **source filename** when embedded `INAM` contains generic titles such as `Sound Bank`. Each imported bank also has a **更名** button, saved as a display-only preference; the original file and MIDI presets are untouched. Old generic labels automatically use their original source filename if available.
- Android CI builds `assembleRelease` (R8 minified, resource shrinking, no debugger), verifies the native libraries and APK signature, and publishes `LivePerformer-v0.4.6.apk` rather than a debug APK. For upgrading the existing private test installation, this optimized release is deliberately signed with the **same publicly available disposable development certificate**, which is **not production signing**. Real production distribution will need a securely stored distinct signing key and may not update in place.
- Tests cover SF3 RIFF sample-table conversion through an injected decoder (no device-level Vorbis test yet), source-name fallback, plus established Kotlin/native tests.

## v0.4.7 — SF3 sample-count progress, codec reuse, readable instrument aliases, suffix filtering

- SF3→SF2 conversion progress now reports **completed samples / total samples** and a corresponding bar. Decoded MiB remains visible separately. The number-based percentage is based on count, **not elapsed time**: individual Vorbis samples may vary widely in length/complexity.
- One MediaCodec object and reusable output buffers are now used for an entire import session; each compressed stream is separately configured, stopped, and restarted to preserve its Ogg headers. This reduces repeated allocations and codec creation; performance is not claimed without on-device measurements. An Android emulator integration test still exercises actual SF3 decoding.
- The import-completion message now renders a real line break rather than literal `\\n`.
- The user-editable SoundFont library label becomes the selected **instrument name** when that SoundFont has exactly one preset. Multi-preset SoundFonts retain their distinct preset labels. Existing tracks are renamed only if their current name matches a default generated label; manually renamed tracks are preserved.
- Android's system document picker filters **MIME types, not filename extensions**. A new in-app SAF folder browser lists only subfolders and allowed `.sf2/.sf3/.zip/.xz` or project `.lvp/.lvpt` files. It needs the user to grant access to a folder. Android 11+ disallows selecting the `Download` root; files directly in Downloads (or cloud providers) still use the separate system picker, which may show unrelated files. The app always validates the selected filename before import.

## v0.4.8 — managed LivePerformer files and Downloads compatibility

- Android 11+ deliberately disables **ACTION_OPEN_DOCUMENT_TREE** for the phone storage root and the `Download` directory. Choosing a single SF2/SF3/ZIP/XZ file with **ACTION_OPEN_DOCUMENT** is still permitted; the primary `從 Downloads／其他位置匯入` button now opens that working system file picker directly (and validates the suffix after selection).
- A real **LivePerformer DocumentsProvider root** is now registered in Android's system Files/SAF browser. It exposes four logical folders:
  - `音色庫（已匯入）`: read-only, includes previously imported SF2 and bundled GeneralUser; no extra copy or migration.
  - `待匯入音色`: writable. Copy SF2/SF3/ZIP/XZ here from Downloads using an Android system Files app; then tap `LivePerformer 待匯入音色` in LivePerformer to select files, filtered by their actual suffix. No global storage permissions required.
  - `專案`: the existing app-managed `.lvp` library from `filesDir/projects`.
  - `音軌範本`: the existing `.lvpt` templates, shown separately without duplicating them.
- External projects imported from Downloads are copied into the internal project library, using unique filenames rather than overwriting existing saved projects. They remain available from the regular saved-project list and the DocumentsProvider location.
- The DocumentsProvider is a **virtual system Files location backed by App-private storage**, not a physical `/storage/emulated/0/Documents/LivePerformer` path. This avoids a second copy of large converted SF2s. It appears as `LivePerformer` among available locations in Android's native Files picker; individual OEM file managers vary in how they display external providers. Data in this root is removed when the app is uninstalled; exporting/copying documents outside it is still recommended for backups.
- Files directly under Downloads, including the user's existing SF3/XZ, stay exactly where they are: use the direct system picker and search. The system picker may show other extensions because Android scopes it by MIME; LivePerformer verifies suffixes before parsing.
- Provider and project import behavior have Android emulator integration tests for root listing, copying to the inbox, duplicate-safe project import, and rejecting unrelated extensions.

## v0.4.9 groundwork — 固定八 Pad 鼓聲、可匯入 SoundFont Drum Map

- MPK mini MKII **Pad Bank A (channel 2, Note 36–43)**: Pad 1 Bass Drum=GM36, 2 Snare=38, 3 Closed Hi-hat=42, 4 Open Hi-hat=46, 5 Low Tom=45, 6 High Tom=50, 7 Ride=51, 8 Crash=49. Only the Pad number comes from the hardware; the sound uses the current kit mapping.
- A matching MPK Bank A uses this assignment automatically when the project has a drum track; the project-level “MPK mini MK2 固定鼓 Pad” preset creates an explicit `drum-pads` route. Historical unmodified Note 36–43 drum passthrough routes are interpreted as these roles and can be upgraded by the preset button. Custom user routes are not replaced automatically. Bank B (Note 44–51 and CC20–35) is not changed.
- Each recorded drum hit stores its **canonical GM role note**, not the target SoundFont's arbitrary sample number. Live monitoring and loop playback apply the selected drum SoundFont map at synth output, allowing switching compatible kits while preserving the eight musical roles.
- **GM is built in** and requires no downloaded mapping. From **System Settings → 音色庫 → 匯入 Map**, choose a `.drummap.json` file from Downloads or Android Files; it is saved in app-wide preferences for that SoundFont and all projects. **還原 GM** removes all overrides for the SoundFont.
- A map normally applies to every drum preset in one SoundFont. If the JSON includes both `"bank":128` and `"program":0` (zero-based), it only overrides that particular SF2 preset, leaving the SoundFont-level mapping as fallback. Imported overrides are not embedded into `.lvp` projects.
- Example downloadable mapping: [gm-standard.drummap.json](drum-maps/gm-standard.drummap.json). Replace the eight `notes` MIDI integers according to a kit's documentation; the `format` and eight semantic keys must remain unchanged. Do **not** use named octave pitches (C2/C3), as naming conventions differ.
- Hi-hat closing sends NoteOff for the mapped open-hat note just before NoteOn for the mapped closed-hat note. Correct SF2 `exclusiveClass` is still necessary to guarantee instant choking for samples that ignore NoteOff. Merely importing a mapping file cannot synthesize missing hi-hat choking behavior in an unsuitable SoundFont.
- No separate Android file-storage permission is needed for importing a mapping; the user explicitly selects the document through the system file picker.

## v0.4.11 — MPK mini MKII four bank roles and eight mixer knobs

**Verified on the user's hardware:** The prior claim that the MKII Pad CC Toggle/Momentary setting necessarily changes Note triggering was incorrect. This MPK editor configuration is **Bank A Momentary, Bank B Toggle**, while Note Bank A still plays Drums and Note Bank B still plays Bass. The official Editor guide documents configurable CC behavior but does not establish the earlier blanket claim.

- **Note Bank A** (MPK Pad MIDI Channel 2, Note 36–43): the fixed GM 8-role drum layout; imported SoundFont maps still work.
- **Note Bank B** (MPK Pad MIDI Channel 2, Note 44–51): eight notes of **Bass C2 major by default** (C2 D2 E2 F2 G2 A2 B2 C3); adjust key, major/natural-minor mode, octave, or individual pitches by editing the existing Bass MIDI route.
- **CC Bank A** (MPK Pad MIDI Channel 2, CC 20–27): Pad1 Play/Stop; 2 Record/Stop; 3 Metronome On/Off; 4–8 Solo tracks 1–5. Each physical press (CC=127) toggles the current app state; release (CC=0) has no effect.
- **CC Bank B** (MPK Pad MIDI Channel 2, CC 28–35): Pad1–7 Mute tracks 1–7 by current displayed track order; Pad8 Mute/Unmute All. Bank B is hardware Toggle: CC127 turns ON, CC0 turns OFF, following controller latch state rather than toggling again in software.
- **Knobs K1–K6** (MPK Keybed/Controls Channel 1): user CC2–7 map to the volume of the first six display-ordered tracks; **K7 (CC8) controls metronome click volume**, while **K8 (CC9) controls master gain 0.0–1.5**. App default is now **CC2–9**; previous CC1–8 and alternate CC70–77 remain selectable. Knobs send continuous values, never toggle.
- In **Project Settings → MIDI Routing**, first apply "Note A 鼓組 / Note B Bass", then "CC A/B 控制與 K1–K8 音量". Applying the second preset explicitly replaces the MPK CC 20–35 and the selected knob 8-CC interval **on the specified device**. It does not overwrite the Note routes or other controls. Afterward, any route can be edited individually: kind, channel, note range, action, target track slot and `ccMode`.
- Individual CC routes support **press-toggle** (Bank A Momentary hardware + app-state toggle), **absolute** (Bank B hardware Toggle; CC127 = ON / CC0 = OFF), **momentary** (press-only) and **legacy** for old project routing. The new preset selects `press-toggle` for CC20–27 and `absolute` for CC28–35.
- Position-based routes follow the current track order. Missing tracks do nothing; new tracks instantly become addressable when inserted at a matching position. The global master knob works even if no track exists.
- **Joystick verified against Akai official Editor manual (p.12):** X Axis Pitchbend sends 14-bit pitch bend to the selected instrument (nominal default ±2 semitones unless the SF2/RPN bend range differs). Y Axis **"CC 2" means *Dual CC mode*, not controller #2**: UP=CC1 (modulation/vibrato depending on SoundFont), DOWN=CC11 (expression). Both are emitted on Keybed/Controls Ch1. In v0.4.17, the MPK DOWN joystick is **inverted** at the app input boundary: hardware value 0 at rest becomes MIDI Expression=127 (full), and physical 127 down becomes MIDI Expression=0 (silence). The converted CC11 values are recordable automation; an explicit user MIDI route still takes precedence. CC1 UP modulation and pitch-bend remain unchanged; these controls do not collide with Knobs CC2–9.
- **Potential future Momentary CC actions:** next/previous track, fill drum, tap tempo, one-shot sample, and clear-with-confirmation. Do not put one-shot actions into an on/off switch by default.

## v0.4.12 — Count-in, compact MIDI meters and adaptive keyboard

- Project settings now include a count-in switch (enabled by default) and one/two-bar count-in (one bar by default). Count-in applies to recording from stopped transport; it respects time signature and uses metronome clicks even if the general metronome is off. Playback starts after count-in, whereas recording in a currently playing loop arms at the next loop boundary without another count-in.
- Each track has a persisted MIDI CC7 volume (0–127) with a narrow meter next to Mute/Solo. The meter fill shows configured volume; a transient dot is **MIDI-note activity estimated from velocity**, not isolated PCM loudness. Muted tracks retain a grey activity indicator. The master meter's dot samples real post-mix audio output peak, with the fill showing configured gain. Both are display hints, not calibrated dB meters.
- The strip above the keyboard always shows the most recent external MIDI event, including keyboard ordinal, MIDI channel, Note/CC and value, with no timeout. MIDI Clock and Active Sensing are excluded.
- Default keyboard range starts at B2 for drum/bass tracks or B3 for others, with user-selected base note remembered in the project for every track. Recorded tracks suggest their lowest recorded pitch unless a manually saved keyboard base already exists. Play range spans two octaves in portrait or three in landscape; monitor range can automatically shrink keys to cover the full recorded/held pitch range, and shows actual lowest/highest MIDI notes.
- Earlier .lvp / .lvpt JSON is readable: absent count-in and volume/base fields default safely. No device-validation claims are made; use Android CI and direct MIDI input/sound tests before relying on stage behavior.

## v0.4.13 — 分頁設定、Tap Tempo、MIDI 路由 JSON 與多軌鍵盤輸入（開發分支）
- 系統設定：映射／音訊／MIDI／音色；專案設定：專案／節拍／MIDI 路由，頂端分頁不再從長頁向下尋找設定。
- 演奏畫面點擊 BPM 區域連續四下，三段間隔平均計算 BPM；「Tap 錄」先待命，第四拍直接錄製，不再加一次 Count-in。一般錄製保留原有 Count-in。CC 路由動作另外提供 tap-tempo、tap-record。
- MIDI 路由使用獨立的 `liveperformer.midi-routes.v1` JSON 匯入／匯出，載入不修改專案音軌、速度或音符；進入 MIDI 路由分頁操作。Akai MPK mini MKII 既有音符／Pad／CC／Knob 路由保留。
- 每個音軌左邊數字可按選取多個鍵盤輸入目的軌，長按拖曳可排序。觸控鍵盤與一般鍵盤輸入只發送給未指定為 Pad 的選取音軌；固定鼓 Pad／Bass Pad 仍走各自路由，但三軌可同時待錄且只記錄各自收到的音符。
- 鍵盤以直條呈現每一個目前按住的 MIDI NoteOn Velocity，而非 PCM 音量；縮窄各軌音量表、縮短琴鍵高度、擴大 Piano Roll。
- 仍須完成 Android CI 與 Galaxy A25 5G、MPK mini MKII 的手動實機測試；目前不能宣稱硬體確認。

## v0.4.14 — 主畫面可拖曳分隔線、力度水平線、完整 C 八度
- 音軌控制欄預設約半個螢幕寬，至少 142dp 且保留至少 90dp Piano Roll；可在垂直分隔線左右拖曳並儲存比例，分隔線延伸至新增音軌與下方空白處，不影響獨立的白色播放位置線。
- 修正音軌名稱被 M/S 及窄音量表壓到只剩省略號，縮短新增音軌列，對齊 BPM／TAP 與 Tap 錄製控制。
- 主音量與音軌 CC7 音量採用向上增加的垂直推桿；窄音量活動顯示保留。
- 鍵盤每個 MIDI NoteOn 的 Velocity 改為水平方向短線，線在琴鍵中的垂直高度代表力度，多音符各自標示，並非輸出音量。
- 鼓／Bass 無錄製預設 C2，其他樂器預設 C3；起點與錄製音域以 C 為八度邊界，PLAY 維持 C 到 C 兩個完整八度，MONITOR 依內容涵蓋完整音域。
- 此版使用既有公開開發測試簽章；CI 通過不等於已經在 Galaxy A25 5G 實機驗證。

## v0.4.16 — source-separated live recording and safe note release

- **Simultaneous armed tracks do not mean broadcast keyboard input:** When Drums are bound to Note Pad A and Bass to Note Pad B, the keybed records and plays only other armed tracks (e.g. Piano/Lead). Pad notes continue going to their dedicated tracks, including when all three numbered targets are armed. The built-in MPK A/B fallback is handled even before saving explicit Pad routes; a non-MPK source without explicit Pad assignments retains normal multi-track behavior.
- **No stuck notes after changing tracks:** Selecting a track, removing an armed target, or changing a preset releases the departed channel (including sustain) instead of leaving a NoteOn forever. Live key releases follow their original destination, never an instrument selected after the key press.
- **Count-in early downbeat:** A NoteOn within the final half of the active quantization subdivision before count-in finishes is captured into tick zero. The same half-grid tolerance applies to an armed recording at the next loop boundary.
- **K7 metronome / K8 master:** The default and preset MPK CC2–9 assignments place K7 on the metronome and K8 on main volume. Compatible historical K7 slot-7 volume rules are migrated on project load. The project settings and landscape live view include a click-volume control.
- Track number touch targets are wider. A track at volume 0 shows a warning and its volume popup includes a one-tap reset to 100.
- Native sample rendering keeps reverb and chorus disabled by default to retain predictable low-latency behavior; higher-quality SoundFonts remain the best fidelity improvement.

### v0.4.16 — MPK joystick expression and physical MIDI keyboard visualization

- MPK DOWN joystick sends CC11=127 while pressed and CC11=0 at rest. Standard MIDI Expression is persistent: CC11=0 silences a channel, independent of its CC7 volume, and returning the joystick to center does not automatically restore sound. In v0.4.16 the application forced CC11=127 for safety, temporarily disabling DOWN expression. v0.4.17 instead **maps CC11=127−raw DOWN joystick value**, preserving natural attenuation and full return to normal on release.
- The live keyboard receives held NoteOn velocity from **physical keybed MIDI input** even if the selected track is muted, assigned to Pad, or excluded from the recording destination set. This monitoring is separate from actual synthesis and saved notes; MPK Pad channel 2 notes do not displace the keybed's raw pitch display. The displayed horizontal lines now use a thicker, high-contrast outline on both white and black keys.

## v0.4.17 — MPK joystick expression, recording, and repeatable control curves

- Standard MIDI CC11 Expression: 0 = silence, 127 = no attenuation relative to saved track/mixer CC7. Unlike CC7, CC11 is a performance controller and supports diminuendo/crescendo.
- MPK mini MKII Y-down transmits **0 in the center** and **127 at full DOWN**. The application's default mapping inverts this to `CC11 = 127 - raw`, so center is normal, pressing down gradually attenuates, and releasing returns to normal. Joystick UP (CC1) and X pitch bend remain unchanged. Deliberately configured CC11 routes override this default.
- CC11 gestures on the keybed's playable track(s) now use the same MIDI input recording and timing path as notes, but **CC11 automation retains unquantized tick timestamps** to preserve smooth dynamics; note quantization is unchanged. During Count-in, CC11 may be captured in the same early-downbeat pre-roll window as notes.
- A recording that contains only Expression replaces the old CC11 automation for that track **without erasing previously recorded notes**. A take containing new notes still follows the existing replace-not-overdub note policy. A neutral Expression value at tick 0 and an automatic restoration to 127 at cycle end prevent expression attenuation from leaking into the next loop.
- Recorded CC11 data is included in existing .lvp JSON event arrays as `[tick, 2, 11, value]`; old project files remain readable and CC7 remains a persistent mixer setting, not an event. SF2/GM drums remain mapped to note pitches only; controllers bypass drum-note mappings.
- Regression tests cover the inverse mapping, raw non-quantized expression timing, and an Android expression-only recording that preserves an existing piano note take.

## v0.4.18 — 持續循環 Replace 錄製、TAP 響拍及主畫面 MIDI 訊息

- **錄製按鈕只會待錄**，不再自行啟動播放。停止狀態按「錄」→ 按「播放」時，依專案的 Count-in 設定進入錄製；按「錄」→ 按四次 **TAP**，第四次直接啟動錄製、不重複 Count-in。
- 錄製不再於一輪結束後自動停止。每輪結束完成該輪資料並繼續錄製；無輸入的輪次原本音軌照播。第一個新 Note On 才取代整軌舊音符、立刻停止舊音軌的殘響，往後依本輪輸入儲存。要錄一段刻意的空白，可使用該軌 Mute／清除操作，錄製不會以「沒輸入」視為刪除。
- 原「Tap 錄」按鈕取消，TAP 移至該位置，每按一下都播放一次節拍，第一下較強；音量較大的短促節拍聲由原生音訊輸出獨立混音，不受 SF2 鼓聲音量、CC7/CC11 影響。
- 主畫面鍵盤上方新增即時 MIDI 訊息，包括外接鍵盤與螢幕觸控的 Note On/Off、CC、Pitch Bend 等，不需要開啟系統設定檢視。
- K7 改成節拍器**開關**（CC 值 ≥64 開、<64 關）。新預設與歷史「K7 節拍器音量」匯入設定皆改用開關；主畫面與專案設定移除節拍器音量滑桿。K8 維持主音量。
- **驗證**：CI 建置與自動化測試不等同 Galaxy A25 5G 實機音量、延遲及外接 MIDI 的驗證。
