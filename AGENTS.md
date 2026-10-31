# LivePerformer guidelines

- Prioritize real, measured MIDI-to-speaker latency and xRun stability on Samsung Galaxy A25 5G.
- Do not allocate memory, block, log, load files or acquire mutexes from the real-time audio callback.
- Keep native MIDI/synthesis components reusable for a future Windows version; isolate Android Oboe/UI APIs.
- v0.1 includes USB MIDI, multitouch keyboard, GM instrument selection, SF2 loading and diagnostics. No Loop yet.
- Use concise PR/change descriptions. Link directly to CI results and APK download.
- Never describe an APK as device-verified without actual device tests.
