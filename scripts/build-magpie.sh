#!/bin/sh
# Builds the headless magpie binary that ships inside the APK.
#
# magpie is a Go program; the APK only carries the finished ELF, so this
# script is the only place that needs a Go toolchain.
#
#   MAGPIE_SRC=/path/to/magpie VERSION=android-1.0 ./scripts/build-magpie.sh
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="${MAGPIE_SRC:-$ROOT/../magpie}"
OUT="${MAGPIE_BIN:-$ROOT/../magpie-bin/magpie}"
GO="${GO:-go}"
VERSION="${VERSION:-android-1.0}"

[ -d "$SRC" ] || { echo "magpie source not found at $SRC" >&2; exit 1; }

command -v "$GO" >/dev/null 2>&1 || { echo "go not found in PATH (set GO=/path/to/go)" >&2; exit 1; }

mkdir -p "$(dirname "$OUT")"

cd "$SRC"

# - CGO off          magpie is pure Go; a static ELF needs no libc
# - GOOS=linux       not "android": GOOS=android emits an ELF that asks for
#                    /system/bin/linker64. GOOS=linux is a plain static ELF
#                    and runs on Android without a dynamic linker.
# - tags nogui       the upstream headless build (Dockerfile): no desktop
#                    webview, no menu bar, no daemon plumbing
# - trimpath         no local paths leaked into the binary
# - ldflags          -s -w strips the symbol table; main.version is what
#                    `magpie version` reports
CGO_ENABLED=0 GOOS=linux GOARCH=arm64 "$GO" build \
    -tags nogui \
    -trimpath \
    -ldflags "-s -w -X main.version=$VERSION" \
    -o "$OUT" .

echo "wrote $OUT ($(du -h "$OUT" | cut -f1))"
