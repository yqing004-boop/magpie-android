#!/bin/sh
# Copies the built ELF into the APK's assets, where it is unpacked on first
# start. Nothing else needs to happen here — the build picks it up as-is.
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BIN="${MAGPIE_BIN:-$ROOT/../magpie-bin/magpie}"
DEST="$ROOT/app/src/main/assets/bin/magpie.bin"

[ -f "$BIN" ] || { echo "no binary at $BIN — run scripts/build-magpie.sh first" >&2; exit 1; }

mkdir -p "$(dirname "$DEST")"
cp "$BIN" "$DEST"
echo "copied $BIN -> $DEST ($(du -h "$DEST" | cut -f1))"
