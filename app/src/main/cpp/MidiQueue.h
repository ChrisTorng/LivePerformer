#pragma once
#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>

namespace lp {

enum class MidiType : uint8_t { NoteOn, NoteOff, Controller, PitchBend, Program, TestPulse, AllSoundsOff, PresetSelect };
struct MidiEvent {
    MidiType type{};
    uint8_t channel{};
    uint8_t data1{};
    uint16_t data2{};
    int64_t arrivalNs{};
};

// Bounded multi-producer/single-consumer queue. Never blocks or allocates.
// Algorithm: per-slot generation counters (Vyukov bounded MPMC).
template <size_t Size = 1024>
class MidiQueue {
    static_assert((Size & (Size - 1)) == 0, "queue capacity must be power of two");
    struct Slot {
        std::atomic<size_t> sequence{};
        MidiEvent value{};
    };
    std::array<Slot, Size> slots_{};
    std::atomic<size_t> write_{0};
    std::atomic<size_t> read_{0};
public:
    MidiQueue() {
        for (size_t i = 0; i < Size; ++i) slots_[i].sequence.store(i, std::memory_order_relaxed);
    }
    bool push(const MidiEvent& value) noexcept {
        size_t p = write_.load(std::memory_order_relaxed);
        for (;;) {
            auto& cell = slots_[p & (Size - 1)];
            const size_t seq = cell.sequence.load(std::memory_order_acquire);
            const intptr_t diff = static_cast<intptr_t>(seq) - static_cast<intptr_t>(p);
            if (diff == 0) {
                if (write_.compare_exchange_weak(p, p + 1, std::memory_order_relaxed)) {
                    cell.value = value;
                    cell.sequence.store(p + 1, std::memory_order_release);
                    return true;
                }
            } else if (diff < 0) return false;
            else p = write_.load(std::memory_order_relaxed);
        }
    }
    bool pop(MidiEvent& result) noexcept {
        size_t p = read_.load(std::memory_order_relaxed);
        auto& cell = slots_[p & (Size - 1)];
        const size_t seq = cell.sequence.load(std::memory_order_acquire);
        if (seq != p + 1) return false;
        result = cell.value;
        read_.store(p + 1, std::memory_order_relaxed);
        cell.sequence.store(p + Size, std::memory_order_release);
        return true;
    }
};

} // namespace lp
