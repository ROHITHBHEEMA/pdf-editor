#!/usr/bin/env bash
# Fails if the debug APK's merged manifest declares any <uses-permission>.
set -euo pipefail
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
LATEST=$(ls "$ANDROID_HOME/build-tools" | sort -V | tail -n 1)
APK="app/build/outputs/apk/debug/app-debug.apk"
OUT=$("$ANDROID_HOME/build-tools/$LATEST/aapt" dump permissions "$APK")
if echo "$OUT" | grep -q '^uses-permission'; then
  echo "$OUT"
  echo "FAIL: APK declares permissions" >&2
  exit 1
fi
echo "OK: no permissions"
