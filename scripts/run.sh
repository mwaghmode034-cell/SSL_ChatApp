#!/usr/bin/env bash
# Build / run helper.   Usage: scripts/run.sh build | server [port] | client [host] [port] | gui | docs | clean
# Compiled classes go to bin/.  The server keeps users, history, groups and its log in data/.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

build() {
    command -v javac >/dev/null || { echo "ERROR: javac not found - install a JDK (Java 8 or newer)" >&2; exit 1; }
    mkdir -p bin
    javac -encoding UTF-8 -d bin @sources.txt
    echo "Build OK -> bin/"
}
[ -f bin/ChatServer.class ] || build

case "${1:-}" in
    build)  build ;;
    server) exec java -cp bin ChatServer "${2:-5000}" ;;
    client) exec java -cp bin ChatClient "${2:-localhost}" "${3:-5000}" ;;
    gui)    exec java -cp bin ChatGUI ;;
    docs)   command -v pdflatex >/dev/null || { echo "ERROR: pdflatex not found - install TeX Live or MiKTeX" >&2; exit 1; }
            for f in docs/report/report_ieee docs/slides/slides; do
                [ -f "$f.tex" ] || continue
                ( cd "$(dirname "$f")" && pdflatex -interaction=nonstopmode "$(basename "$f").tex" >/dev/null \
                    && pdflatex -interaction=nonstopmode "$(basename "$f").tex" >/dev/null \
                    && rm -f ./*.aux ./*.log ./*.out ./*.toc ./*.nav ./*.snm ./*.vrb )
                echo "Built $f.pdf"
            done ;;
    clean)  rm -rf bin data; echo "Removed bin/ and data/" ;;
    *)      echo "Usage: $0 build | server [port] | client [host] [port] | gui | docs | clean"; exit 1 ;;
esac
