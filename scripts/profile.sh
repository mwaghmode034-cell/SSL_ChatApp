#!/usr/bin/env bash
# Simple profiler (like gprof's flat profile): runtime of each server method.
# Usage: scripts/profile.sh [clients] [messages-per-client]     (default 20 x 500)
# Client / GUI: add -Dprofile=true (see docs/PROFILING.md).
# Result: docs/profiling/profile.txt  = calls, total ms and average ms per method.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
CLIENTS=${1:-20}; MSGS=${2:-500}; PORT=5990
OUT=docs/profiling/profile.txt; mkdir -p docs/profiling bin
javac -encoding UTF-8 -d bin @sources.txt
TMP=$(mktemp -d); trap 'kill $PID 2>/dev/null || true; rm -rf "$TMP"' EXIT

java -Dprofile=true -Dprofile.out="$PWD/$OUT" -Ddata.dir="$TMP" -cp bin ChatServer $PORT >"$TMP/server.out" 2>&1 &
PID=$!
for _ in $(seq 1 50); do (echo > /dev/tcp/localhost/$PORT) 2>/dev/null && break; sleep 0.2; done

echo "Load: $CLIENTS clients x $MSGS messages ..."
java -cp bin LoadTest $PORT "$CLIENTS" "$MSGS" | grep -v '^RESULT'
java -cp bin ProfActions $PORT      # group create/join/leave, /msg, /history, ... a few times

kill -TERM $PID; wait $PID 2>/dev/null || true      # server prints the table when it stops
echo; echo "=== Runtime per method (saved in $OUT) ==="
cat "$OUT"
