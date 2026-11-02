# LivePerformer — Android low-latency MIDI instrument and looper (v0.2.1)

**Status: early source prototype, not yet device-verified.** Initial target: Samsung Galaxy A25 5G, USB MIDI keyboard, wired 3.5 mm headphones. v0.2.1 adds an ergonomic single-screen interface and v0.2 includes an experimental MIDI-only loop recorder; physical device performance remains unverified.

## MVP

- Multitrack MIDI loop: play/pause, one-cycle recording/overdub, clear, tempo (40–240 BPM), 1/2/4/8 bars, metronome toggle, per-track mute, add/select/delete instruments. Default 120 BPM, two bars, and a one-bar drum pattern repeated twice. Track MIDI clips and settings persist locally. Playback uses a 4 ms Android scheduler and is **not sample-accurate**; audio synthesis remains native.
- Native Oboe / AAudio low-latency stereo output (try Exclusive, fall back to Shared).
- FluidSynth 2.4.6 SF2 synthesis via a directly linked Android Prefab AAR.
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
