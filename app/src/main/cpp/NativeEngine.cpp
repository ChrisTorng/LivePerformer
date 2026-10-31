#include "MidiQueue.h"
#include <oboe/Oboe.h>
#include <jni.h>
#include <android/log.h>
#include <fluidsynth.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

namespace {
constexpr const char* TAG = "LivePerformer";
using Clock = std::chrono::steady_clock;
int64_t nowNs() noexcept {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now().time_since_epoch()).count();
}


// Load the library delivered by fluidsynth-android AAR at runtime.
// This lets the C++ core keep a narrow, portable FluidSynth API boundary.
struct FluidApi {
    bool initialized{};
    fluid_settings_t* (*new_settings)(){};
    void (*delete_settings)(fluid_settings_t*){};
    int (*settings_setnum)(fluid_settings_t*, const char*, double){};
    int (*settings_setint)(fluid_settings_t*, const char*, int){};
    fluid_synth_t* (*new_synth)(fluid_settings_t*){};
    void (*delete_synth)(fluid_synth_t*){};
    int (*sfload)(fluid_synth_t*, const char*, int){};
    int (*program)(fluid_synth_t*, int, int){};
    int (*noteon)(fluid_synth_t*, int, int, int){};
    int (*noteoff)(fluid_synth_t*, int, int){};
    int (*cc)(fluid_synth_t*, int, int, int){};
    int (*pitch_bend)(fluid_synth_t*, int, int){};
    int (*sounds_off)(fluid_synth_t*, int){};
    int (*write_float)(fluid_synth_t*, int, void*, int, int, void*, int, int){};

    bool init() {
        if (initialized) return true;
        new_settings = &new_fluid_settings;
        delete_settings = &delete_fluid_settings;
        settings_setnum = &fluid_settings_setnum;
        settings_setint = &fluid_settings_setint;
        new_synth = &new_fluid_synth;
        delete_synth = &delete_fluid_synth;
        sfload = &fluid_synth_sfload;
        program = &fluid_synth_program_change;
        noteon = &fluid_synth_noteon;
        noteoff = &fluid_synth_noteoff;
        cc = &fluid_synth_cc;
        pitch_bend = &fluid_synth_pitch_bend;
        sounds_off = &fluid_synth_all_sounds_off;
        write_float = &fluid_synth_write_float;
        initialized = true;
        return true;
    }
};

// A synth is created on a worker thread and swapped exclusively by the audio callback.
struct SynthInstance {
    FluidApi* api;
    fluid_settings_t* settings;
    fluid_synth_t* synth;
    explicit SynthInstance(FluidApi* a, int rate) : api(a), settings(a->new_settings()), synth(nullptr) {
        if (!settings) return;
        api->settings_setnum(settings, "synth.sample-rate", rate);
        api->settings_setnum(settings, "synth.gain", 0.35);
        api->settings_setint(settings, "synth.polyphony", 128);
        api->settings_setint(settings, "synth.reverb.active", 0);
        api->settings_setint(settings, "synth.chorus.active", 0);
        api->settings_setint(settings, "synth.cpu-cores", 1);
        synth = api->new_synth(settings);
    }
    ~SynthInstance() {
        if (synth) api->delete_synth(synth);
        if (settings) api->delete_settings(settings);
    }
    bool valid() const noexcept { return synth != nullptr; }
};

class Engine final : public oboe::AudioStreamDataCallback {
    FluidApi api_;
    lp::MidiQueue<2048> events_;
    std::shared_ptr<oboe::AudioStream> stream_;
    std::mutex controlMutex_;
    // Exactly one pending load at a time; owner thread waits until it is accepted.
    std::atomic<SynthInstance*> pending_{nullptr};
    std::atomic<SynthInstance*> retired_{nullptr};
    SynthInstance* active_{nullptr}; // Only touched by the callback when running.
    std::atomic<bool> running_{false};
    std::atomic<bool> overrun_{false};
    std::atomic<int64_t> lastDispatchUs_{-1};
    std::atomic<uint64_t> dropped_{0};
    std::atomic<int> sampleRate_{48000};
    std::atomic<int> testSamples_{0};
    std::atomic<int64_t> lastPulseNs_{0};
    std::atomic<uint32_t> noiseState_{1};

public:
    bool start() {
        std::lock_guard<std::mutex> lock(controlMutex_);
        if (running_) return true;
        if (!api_.init()) return false;
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output);
        builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
        builder.setSharingMode(oboe::SharingMode::Exclusive);
        builder.setFormat(oboe::AudioFormat::Float);
        builder.setChannelCount(2);
        builder.setSampleRate(oboe::kUnspecified);
        builder.setDataCallback(this);
        auto result = builder.openStream(stream_);
        if (result != oboe::Result::OK) {
            // Some devices reject exclusive streams; retry Shared low latency.
            builder.setSharingMode(oboe::SharingMode::Shared);
            result = builder.openStream(stream_);
            if (result != oboe::Result::OK) return false;
        }
        sampleRate_.store(stream_->getSampleRate());
        const int burst = stream_->getFramesPerBurst();
        if (burst > 0) stream_->setBufferSizeInFrames(burst * 2);
        if (stream_->requestStart() != oboe::Result::OK) { stream_->close(); stream_.reset(); return false; }
        running_.store(true);
        return true;
    }
    void stop() {
        std::lock_guard<std::mutex> lock(controlMutex_);
        running_.store(false);
        if (stream_) { stream_->requestStop(); stream_->close(); stream_.reset(); }
        delete pending_.exchange(nullptr);
        delete retired_.exchange(nullptr);
        delete active_;
        active_ = nullptr;
    }
    bool loadFont(const char* filename) {
        if (!api_.init()) return false;
        if (!running_) return false;
        // The UI serializes loads; the audio callback must never block for disk IO.
        auto* ready = new SynthInstance(&api_, sampleRate_.load());
        if (!ready->valid() || api_.sfload(ready->synth, filename, 1) < 0) { delete ready; return false; }
        // Wait only on the background loader. Never wait in a callback/UI thread.
        while (pending_.load(std::memory_order_acquire) || retired_.load(std::memory_order_acquire)) {
            collectRetired();
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
        }
        pending_.store(ready, std::memory_order_release);
        return true;
    }
    void collectRetired() {
        auto* old = retired_.exchange(nullptr, std::memory_order_acq_rel);
        delete old; // Must never be called from onAudioReady.
    }
    void note(lp::MidiType type, int channel, int a, int b) noexcept {
        lp::MidiEvent ev{type, static_cast<uint8_t>(channel & 15),
            static_cast<uint8_t>(a & 127), static_cast<uint16_t>(b & 16383), nowNs()};
        if (!events_.push(ev)) { dropped_.fetch_add(1); overrun_.store(true); }
    }
    void silence() noexcept { note(lp::MidiType::AllSoundsOff, 0, 0, 0); }
    int64_t lastPulseNs() const noexcept { return lastPulseNs_.load(std::memory_order_acquire); }
    std::string status() {
        std::lock_guard<std::mutex> lock(controlMutex_);
        if (!stream_) return "Audio: stopped";
        char buf[300];
        auto xr = stream_->getXRunCount();
        const int xruns = xr.error() == oboe::Result::OK ? xr.value() : -1;
        std::snprintf(buf, sizeof(buf), "%d Hz · burst %d · buffer %d frames · %s · %s · xRuns %d · MIDI→callback %lld µs · drops %llu",
            stream_->getSampleRate(), stream_->getFramesPerBurst(), stream_->getBufferSizeInFrames(),
            stream_->getAudioApi() == oboe::AudioApi::AAudio ? "AAudio" : "OpenSL ES",
            stream_->getSharingMode() == oboe::SharingMode::Exclusive ? "Exclusive" : "Shared",
            xruns, static_cast<long long>(lastDispatchUs_.load()),
            static_cast<unsigned long long>(dropped_.load()));
        return buf;
    }
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream*, void* data, int32_t frames) override {
        auto* output = static_cast<float*>(data);
        if (auto* next = pending_.exchange(nullptr, std::memory_order_acq_rel)) {
            auto* prior = active_;
            active_ = next;
            // Prior synth is retired on the worker, never deleted on the audio thread.
            if (prior) retired_.store(prior, std::memory_order_release);
        }
        if (overrun_.exchange(false)) {
            if (active_) for (int ch = 0; ch < 16; ++ch) api_.sounds_off(active_->synth, ch);
        }
        lp::MidiEvent event;
        // MIDI processing is buffer-quantized in v0.1. Future versions will schedule sample offsets.
        while (events_.pop(event)) {
            if (event.type == lp::MidiType::TestPulse) {
                testSamples_.store(sampleRate_.load() / 50);
                lastPulseNs_.store(nowNs(), std::memory_order_release);
                continue;
            }
            if (!active_) continue;
            const int c = event.channel;
            switch (event.type) {
                case lp::MidiType::NoteOn: api_.noteon(active_->synth, c, event.data1, event.data2); break;
                case lp::MidiType::NoteOff: api_.noteoff(active_->synth, c, event.data1); break;
                case lp::MidiType::Controller: api_.cc(active_->synth, c, event.data1, event.data2); break;
                case lp::MidiType::PitchBend: api_.pitch_bend(active_->synth, c, event.data2); break;
                case lp::MidiType::Program: api_.program(active_->synth, c, event.data1); break;
                case lp::MidiType::AllSoundsOff:
                    for (int ch = 0; ch < 16; ++ch) api_.sounds_off(active_->synth, ch);
                    break;
                case lp::MidiType::TestPulse: break;
            }
            if (event.type == lp::MidiType::NoteOn)
                lastDispatchUs_.store((nowNs() - event.arrivalNs) / 1000, std::memory_order_relaxed);
        }
        if (active_) api_.write_float(active_->synth, frames, output, 0, 2, output, 1, 2);
        else std::memset(output, 0, sizeof(float) * frames * 2);
        int remaining = testSamples_.load(std::memory_order_relaxed);
        const int sampleRate = sampleRate_.load(std::memory_order_relaxed);
        const int chirpLength = std::max(1, sampleRate / 50);
        constexpr double PI = 3.14159265358979323846;
        const double duration = double(chirpLength) / sampleRate;
        for (int i = 0; i < frames && remaining > 0; ++i, --remaining) {
            const int p = chirpLength - remaining;
            const double t = double(p) / sampleRate;
            const double edge = std::sin(PI * p / chirpLength);
            const double phase = 2.0 * PI * (600.0 * t + 0.5 * 4000.0 * t * t / duration);
            const float signal = static_cast<float>(0.24 * edge * edge * std::sin(phase));
            output[2*i] += signal;
            output[2*i+1] += signal;
        }
        testSamples_.store(remaining, std::memory_order_relaxed);
        return oboe::DataCallbackResult::Continue;
    }
};

Engine& engine() { static Engine instance; return instance; }
} // namespace

extern "C" {
JNIEXPORT jboolean JNICALL Java_tw_idv_christorng_liveperformer_NativeEngine_start(JNIEnv*, jobject) { return engine().start(); }
JNIEXPORT void JNICALL Java_tw_idv_christorng_liveperformer_NativeEngine_stop(JNIEnv*, jobject) { engine().stop(); }
JNIEXPORT jboolean JNICALL Java_tw_idv_christorng_liveperformer_NativeEngine_loadSf2(JNIEnv* env, jobject, jstring path) {
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    const bool ok = engine().loadFont(cpath);
    env->ReleaseStringUTFChars(path, cpath);
    return ok;
}
JNIEXPORT void JNICALL Java_tw_idv_christorng_liveperformer_NativeEngine_collectRetired(JNIEnv*, jobject) { engine().collectRetired(); }
JNIEXPORT void JNICALL Java_tw_idv_christorng_liveperformer_NativeEngine_midi(JNIEnv*, jobject, jint type, jint channel, jint a, jint b) {
    if (type < 0 || type > 6) return;
    engine().note(static_cast<lp::MidiType>(type), channel, a, b);
}
JNIEXPORT jlong JNICALL Java_tw_idv_christorng_liveperformer_NativeEngine_lastPulseNs(JNIEnv*, jobject) {
    return engine().lastPulseNs();
}
JNIEXPORT jstring JNICALL Java_tw_idv_christorng_liveperformer_NativeEngine_status(JNIEnv* env, jobject) {
    return env->NewStringUTF(engine().status().c_str());
}
}
