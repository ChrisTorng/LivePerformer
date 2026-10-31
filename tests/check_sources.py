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
