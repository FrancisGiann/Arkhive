#!/usr/bin/env bash
set -euo pipefail

readingapp_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$readingapp_root"

if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/javac" ]]; then
  printf '%s\n' 'Set JAVA_HOME to a JDK 21 installation (with javac), then run again.' >&2
  exit 1
fi
readingapp_sdk="${READINGAPP_SDK_ROOT:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}}"
if [[ -z "$readingapp_sdk" || ! -f "$readingapp_sdk/platforms/android-37.2/android.jar" ]]; then
  printf '%s\n' 'Set READINGAPP_SDK_ROOT to an SDK with platform 37.2, NDK 29.0.14206865 and build tools 36.0.0 or newer.' >&2
  exit 1
fi

export ANDROID_HOME="$readingapp_sdk"
export GRADLE_USER_HOME="${READINGAPP_GRADLE_HOME:-$readingapp_root/.readingapp-cache/gradle}"
export ANDROID_USER_HOME="${READINGAPP_ANDROID_USER_HOME:-$readingapp_root/.readingapp-cache/android-user}"
mkdir -p "$GRADLE_USER_HOME" "$ANDROID_USER_HOME"
printf 'sdk.dir=%s\n' "$readingapp_sdk" > local.properties

if [[ $# -eq 0 ]]; then
  set -- :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
fi
exec ./gradlew "$@" -Pdist=local -Pinclude-telemetry=false -Penable-updater=false --console=plain
