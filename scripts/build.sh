#!/usr/bin/env bash
# Builds AKZSA from the repository as-is. This script NEVER edits source files.
# Usage: scripts/build.sh [test|debug|release-aab|release-apk|lint]
set -euo pipefail
cd "$(dirname "$0")/../AKZSA-Android"
: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-}}"
if [ -z "$ANDROID_HOME" ] && [ ! -f local.properties ]; then
  echo "ERROR: Android SDK not found. Install Android Studio, or set ANDROID_HOME, or create local.properties (sdk.dir=...)." >&2; exit 2
fi
java -version 2>&1 | head -1
case "${1:-test}" in
  test)        ./gradlew --no-daemon :app:testFullDebugUnitTest ;;
  debug)       ./gradlew --no-daemon :app:assembleFullDebug ;;   # sideload build: all-files access; installs over older debug builds
  lint)        ./gradlew --no-daemon :app:lintPlayRelease ;;
  release-aab) ./gradlew --no-daemon :app:bundlePlayRelease ;;
  release-apk) ./gradlew --no-daemon :app:assemblePlayRelease ;;
  *) echo "usage: $0 [test|debug|lint|release-aab|release-apk]"; exit 1 ;;
esac
