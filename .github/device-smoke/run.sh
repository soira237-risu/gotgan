#!/usr/bin/env bash
# Installs the debug app and its device tests on the running emulator and runs the classes in tests.txt.
# Called by the device-smoke job in .github/workflows/android.yml; works the same against a local emulator.
set -euo pipefail
cd "$(dirname "$0")/../.."
out=app/build/device-smoke
mkdir -p "$out"

./gradlew :app:installDebug :app:installDebugAndroidTest --stacktrace
# Device tests browse shared storage like the user would; grant what the app asks for at first start.
adb shell appops set com.quickfiles.app MANAGE_EXTERNAL_STORAGE allow
adb shell pm grant com.quickfiles.app android.permission.POST_NOTIFICATIONS || true
adb logcat -c || true

tests=$(grep -v '^\s*#' .github/device-smoke/tests.txt | sed 's/\s//g' | grep -v '^$' | sed 's/^/com.quickfiles.app./' | paste -sd, -)
echo "Running: $tests"
adb shell am instrument -w -r -e class "$tests" com.quickfiles.app.test/com.quickfiles.app.GotganTestRunner | tee "$out/instrumentation.txt"
adb logcat -d > "$out/logcat.txt" || true

# `am instrument` exits 0 even when tests fail, so read its report.
if grep -q 'FAILURES!!!\|INSTRUMENTATION_FAILED\|Process crashed' "$out/instrumentation.txt" || ! grep -q '^OK (' "$out/instrumentation.txt"; then
  echo "::error::Device tests failed; see the device-smoke artifact"
  grep -A20 'INSTRUMENTATION_STATUS: stack=' "$out/instrumentation.txt" | head -150 || true
  exit 1
fi
