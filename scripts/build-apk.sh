#!/bin/sh
# Builds the release APK. Needs a local Android SDK (ANDROID_HOME / local.properties)
# and the Gradle wrapper jar — see README.
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

"$ROOT/scripts/build-magpie.sh"
"$ROOT/scripts/sync-binary.sh"

exec ./gradlew assembleRelease "$@"
