#include "../app/src/main/cpp/MidiQueue.h"
#include <cassert>
#include <atomic>
#include <thread>
#include <vector>
#include <iostream>
int main() {
    lp::MidiQueue<1024> q;
    lp::MidiEvent e;
    assert(!q.pop(e));
    for (int n = 0; n < 1024; ++n)
        assert(q.push({lp::MidiType::NoteOn, 0, static_cast<uint8_t>(n % 128), 100, n}));
    assert(!q.push({}));
    for (int n = 0; n < 1024; ++n) { assert(q.pop(e)); assert(e.arrivalNs == n); }
    assert(!q.pop(e));
    constexpr int producers = 4, perProducer = 20000;
    std::atomic<int> finished{0}, received{0};
    std::thread consumer([&] {
        while (received < producers * perProducer) {
            if (q.pop(e)) ++received;
            else std::this_thread::yield();
        }
    });
    std::vector<std::thread> threads;
    for (int p = 0; p < producers; ++p) threads.emplace_back([&, p] {
        for (int n = 0; n < perProducer; ++n) {
            lp::MidiEvent m{lp::MidiType::NoteOn, static_cast<uint8_t>(p), 60, 100, n};
            while (!q.push(m)) std::this_thread::yield();
        }
        ++finished;
    });
    for (auto& t : threads) t.join();
    consumer.join();
    assert(finished == producers && received == producers * perProducer);
    std::cout << "PASS: MIDI queue (4 producers, 80000 events, full/empty/wrap)\n";
}
