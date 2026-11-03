# LivePerformer guidelines

- Prioritize real, measured MIDI-to-speaker latency and xRun stability on Samsung Galaxy A25 5G.
- Do not allocate memory, block, log, load files or acquire mutexes from the real-time audio callback.
- Keep native MIDI/synthesis components reusable for a future Windows version; isolate Android Oboe/UI APIs.
- v0.4 includes multitrack MIDI looping, live piano-roll, multi-Solo, draggable track order, project JSON and imported SF2 presets.
- Keep the landscape performance screen free of outer-page scrolling. Transport controls must have large, separated touch targets; keep Mute/Solo one tap away. Separate project settings from system-wide settings and guard unsaved takes before loading.
- Use concise PR/change descriptions. Link directly to CI results and APK download.
- Never describe an APK as device-verified without actual device tests.

- The development signing key is publicly recoverable from the dedicated GitHub prerelease and is ONLY for disposable development builds. Do not treat it as a secret or use it to sign production builds. Future production builds must use a private keystore in repository secrets.
