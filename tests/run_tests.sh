#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
g++ -std=c++17 -O2 -Wall -Wextra -Werror -pthread tests/midi_queue_test.cpp -o /tmp/liveperformer-midi-test
/tmp/liveperformer-midi-test
python3 tests/check_sources.py
