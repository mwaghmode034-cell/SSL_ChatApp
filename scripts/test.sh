#!/usr/bin/env bash
# Automated functional tests: build, start a fresh server, run tests, restart server, check persistence.
# Usage: scripts/test.sh [port]     (exit code 0 = all passed)
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN="$ROOT/bin"
PORT="${1:-5999}"
WORK="$(mktemp -d)"; PID=""
trap '[ -n "$PID" ] && kill "$PID" 2>/dev/null; rm -rf "$WORK"' EXIT

"$ROOT/scripts/run.sh" build || exit 1

start() {   # start server in the temp dir and wait until the port accepts connections
    (cd "$WORK" && exec java -cp "$BIN" ChatServer "$PORT" >server.out 2>&1) &
    PID=$!
    for _ in $(seq 100); do (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null && return 0; sleep 0.1; done
    echo "Server failed to start:"; cat "$WORK/server.out"; exit 1
}
stop() { kill "$PID"; wait "$PID" 2>/dev/null; PID=""; }

FAIL=0
echo "== Functional tests =="
start;  java -cp "$BIN" ChatTest "$PORT" main    || FAIL=1
echo "== Persistence after server restart =="
stop;   start
java -cp "$BIN" ChatTest "$PORT" persist         || FAIL=1
stop

echo "== Runtime files =="
for f in users.txt history.log groups.txt server.log; do
    if [ -s "$WORK/$f" ]; then echo "  PASS  $f created"; else echo "  FAIL  $f missing"; FAIL=1; fi
done
if grep -q pw123 "$WORK/users.txt"; then echo "  FAIL  password stored in clear text"; FAIL=1; else echo "  PASS  passwords are hashed"; fi

[ "$FAIL" -eq 0 ] && echo "ALL TESTS PASSED" || echo "SOME TESTS FAILED"
exit "$FAIL"
