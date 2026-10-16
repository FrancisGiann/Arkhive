#!/usr/bin/env bash
set -euo pipefail

readingapp_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
readingapp_sdk="${READINGAPP_SDK_ROOT:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}}"
readingapp_serial="${ANDROID_SERIAL:-}"
if [[ "$readingapp_serial" != emulator-* ]]; then
  printf '%s\n' 'Set ANDROID_SERIAL to the explicitly selected clean emulator.' >&2
  exit 1
fi
readingapp_adb="$readingapp_sdk/platform-tools/adb"
if [[ ! -x "$readingapp_adb" ]]; then
  printf '%s\n' 'Set READINGAPP_SDK_ROOT to the Android SDK.' >&2
  exit 1
fi
if [[ "$("$readingapp_adb" -s "$readingapp_serial" shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ]]; then
  printf '%s\n' 'The selected device is not an Android emulator.' >&2
  exit 1
fi
readingapp_expected_avd="${READINGAPP_EXPECTED_AVD:-}"
if [[ -z "$readingapp_expected_avd" ]]; then
  printf '%s\n' 'Set READINGAPP_EXPECTED_AVD to the explicitly selected private AVD name.' >&2
  exit 1
fi
if [[ "$("$readingapp_adb" -s "$readingapp_serial" emu avd name | tr -d '\r' | head -n 1)" != "$readingapp_expected_avd" ]]; then
  printf '%s\n' 'The selected emulator does not match the expected AVD. No APKs were installed.' >&2
  exit 1
fi
if [[ "$("$readingapp_adb" -s "$readingapp_serial" shell getprop sys.boot_completed | tr -d '\r')" != 1 ]]; then
  printf '%s\n' 'Wait for the selected emulator to finish booting.' >&2
  exit 1
fi
if [[ "$("$readingapp_adb" -s "$readingapp_serial" shell getprop debug.hwui.drawing_enabled | tr -d '\r')" == 0 ]]; then
  printf '%s\n' 'The test image disables drawing. Use a rendering-enabled emulator for the decoded-page UI test.' >&2
  exit 1
fi

"$readingapp_adb" -s "$readingapp_serial" install -r "$readingapp_root/app/build/outputs/apk/debug/app-universal-debug.apk"
"$readingapp_adb" -s "$readingapp_serial" install -r "$readingapp_root/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
mkdir -p "$readingapp_root/.readingapp-cache"
readingapp_report="$readingapp_root/.readingapp-cache/android-runtime-tests.txt"
"$readingapp_adb" -s "$readingapp_serial" shell am instrument -w -r \
  -e class mihon.readingapp.compat.ControlledExtensionRuntimeTest,mihon.readingapp.compat.OriginalDemoReaderContractTest,mihon.readingapp.smoke.PrototypeReaderSmokeTest,mihon.readingapp.books.LocalBookRuntimeTest,mihon.readingapp.discovery.PublicDiscoveryRuntimeTest \
  dev.francisgiann.readingapp.dev.test/androidx.test.runner.AndroidJUnitRunner | tee "$readingapp_report"
if rg -q 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE: -[12]|shortMsg=Process crashed' "$readingapp_report"; then
  exit 1
fi
if ! rg -q 'OK \(16 tests\)' "$readingapp_report"; then
  printf '%s\n' 'The runner did not confirm all sixteen tests; inspect its report.' >&2
  exit 1
fi
