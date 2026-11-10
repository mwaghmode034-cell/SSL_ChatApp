#!/usr/bin/env bash
# Build / run helper.   Usage: scripts/run.sh build | server [port] | client [host] [port] | gui | clean
# The server keeps users.txt, history.log, groups.txt and server.log in ./data
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN="$ROOT/bin"

build() {
    command -v javac >/dev/null || { echo "ERROR: javac not found - install a JDK (Java 8+)" >&2; exit 1; }
    mkdir -p "$BIN"
    javac -encoding UTF-8 -d "$BIN" "$ROOT"/Chat*.java "$ROOT"/tests/ChatTest.java
    echo "Build OK -> $BIN"
}
[ -f "$BIN/ChatServer.class" ] || build

case "${1:-}" in
    build)  build ;;
    server) mkdir -p "$ROOT/data" && cd "$ROOT/data" && exec java -cp "$BIN" ChatServer "${2:-5000}" ;;
    client) exec java -cp "$BIN" ChatClient "${2:-localhost}" "${3:-5000}" ;;
    gui)    exec java -cp "$BIN" ChatGUI ;;
    clean)  rm -rf "$BIN" "$ROOT/data"; echo "Removed bin/ and data/" ;;
    *)      echo "Usage: $0 build | server [port] | client [host] [port] | gui | clean"; exit 1 ;;
esac
