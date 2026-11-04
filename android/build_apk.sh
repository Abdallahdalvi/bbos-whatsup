#!/usr/bin/env sh
set -eu
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$SCRIPT_DIR"
if ! command -v java >/dev/null 2>&1; then echo "JDK 17 is required (java not found)." >&2; exit 2; fi
JAVA_VERSION=$(java -version 2>&1 | head -n 1)
case "$JAVA_VERSION" in *'"17'*) ;; *) echo "JDK 17 is required; found: $JAVA_VERSION" >&2; exit 2;; esac
if [ -z "${ANDROID_SDK_ROOT:-}" ]; then
  if [ -n "${ANDROID_HOME:-}" ]; then ANDROID_SDK_ROOT=$ANDROID_HOME
  elif [ -d "$HOME/Android/Sdk" ]; then ANDROID_SDK_ROOT="$HOME/Android/Sdk"
  else echo "Set ANDROID_SDK_ROOT to an installed Android SDK." >&2; exit 2; fi
  export ANDROID_SDK_ROOT
fi
if [ ! -f "$ANDROID_SDK_ROOT/platforms/android-34/android.jar" ]; then echo "Android SDK Platform 34 not found under $ANDROID_SDK_ROOT" >&2; exit 2; fi
TOOLS="$ANDROID_SDK_ROOT/build-tools/34.0.0"
[ -x "$TOOLS/apksigner" ] && [ -x "$TOOLS/aapt" ] || { echo "Android Build Tools 34.0.0 (aapt, apksigner) are required." >&2; exit 2; }
sh ./gradlew --no-daemon clean compileDebugJavaWithJavac processDebugResources assembleDebug
mkdir -p output
cp app/build/outputs/apk/debug/app-debug.apk output/Q20Messenger.apk
"$TOOLS/apksigner" verify --verbose output/Q20Messenger.apk
BADGING=$("$TOOLS/aapt" dump badging output/Q20Messenger.apk)
printf '%s\n' "$BADGING" | grep -q "sdkVersion:'18'" || { echo "APK minimum SDK is not 18" >&2; exit 1; }
printf '%s\n' "$BADGING" | grep -q "targetSdkVersion:'18'" || { echo "APK target SDK is not 18" >&2; exit 1; }
printf '%s\n' "$BADGING" | grep "package: name="
printf 'Built and verified %s/output/Q20Messenger.apk\n' "$SCRIPT_DIR"
