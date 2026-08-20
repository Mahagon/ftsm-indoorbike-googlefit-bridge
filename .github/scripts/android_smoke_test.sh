#!/usr/bin/env bash
set -euo pipefail

diagnostics="$RUNNER_TEMP/smoke-diagnostics"

collect_diagnostics() {
  mkdir -p "$diagnostics"
  cp -R "$HOME/.android/avd" "$diagnostics/avd" 2>/dev/null || true
  adb logcat -b all -d > "$diagnostics/logcat.txt" 2>&1 || true
  adb shell dumpsys activity crashes > "$diagnostics/activity-crashes.txt" 2>&1 || true
  adb shell dumpsys dropbox --print data_app_crash system_app_crash > "$diagnostics/dropbox-crashes.txt" 2>&1 || true
  adb shell getprop > "$diagnostics/device-properties.txt" 2>&1 || true
  adb shell df -h /data > "$diagnostics/data-filesystem.txt" 2>&1 || true
}

trap collect_diagnostics EXIT

avd_target="$(grep -R '^target=' "$HOME/.android/avd" | head -1 || true)"
echo "Resolved AVD target: $avd_target"
[[ "$avd_target" == *"android-37.0"* ]]
grep -R -q '^hw.device.name=pixel_9$' "$HOME/.android/avd"
./gradlew connectedDebugAndroidTest --no-daemon --stacktrace --info
