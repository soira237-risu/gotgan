#!/usr/bin/env bash
# Runs device test classes on the connected emulator or phone, one class at a time.
#   run.sh                 classes in .github/device-tests/emulator.txt (what CI requires)
#   run.sh all             every *Test class under app/src/androidTest
#   run.sh A,B#method      the named classes or methods
# Expects the debug app and test APKs to be built already (:app:assembleDebug :app:assembleDebugAndroidTest).
# A class that hangs is stopped after DEVICE_TEST_TIMEOUT seconds (default 300) so the others still run.
# DEVICE_TEST_SHARD=i/n runs only part of the list (see below).
# DEVICE_TEST_REPORT_ONLY=1 reports results without failing (used to survey which classes work on an emulator).
set -uo pipefail
cd "$(dirname "$0")/../.."

pkg=com.quickfiles.app
runner=$pkg.test/$pkg.GotganTestRunner
out=app/build/device-tests
limit=${DEVICE_TEST_TIMEOUT:-300}
selection=${1:-}
rm -rf "$out"; mkdir -p "$out"

if [[ -z "$selection" ]]; then
  classes=$(grep -v '^\s*#' .github/device-tests/emulator.txt | sed 's/\s//g' | grep -v '^$')
elif [[ "$selection" == all ]]; then
  classes=$(grep -l '@Test' app/src/androidTest/java/com/quickfiles/app/*.kt | xargs -n1 basename | sed 's/\.kt$//' | sort)
else
  classes=$(tr ',' '\n' <<<"$selection" | sed 's/\s//g' | grep -v '^$')
fi
# DEVICE_TEST_SHARD=i/n keeps every n-th class starting at i (0-based) so CI can split a long list.
if [[ -n "${DEVICE_TEST_SHARD:-}" ]]; then
  classes=$(awk -v i="${DEVICE_TEST_SHARD%/*}" -v n="${DEVICE_TEST_SHARD#*/}" '(NR-1)%n==i' <<<"$classes")
fi
[[ -n "$classes" ]] || { echo "::error::No device test classes selected"; exit 1; }

# Root (emulator images allow it) lets us dump the stacks of a hung test; a phone simply says no.
adb root >/dev/null 2>&1 && adb wait-for-device
adb install -r -t -g app/build/outputs/apk/debug/app-debug.apk || exit 1
adb install -r -t -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk || exit 1
# Device tests browse shared storage like the user would; grant what the app asks for at first start.
adb shell appops set $pkg MANAGE_EXTERNAL_STORAGE allow
adb shell pm grant $pkg android.permission.POST_NOTIFICATIONS 2>/dev/null || true

adb logcat -c 2>/dev/null || true
adb logcat -v threadtime > "$out/logcat.txt" 2>&1 &
logcat_pid=$!
trap 'kill $logcat_pid 2>/dev/null' EXIT

declare -a passed=() failed=() hung=()
summary="$out/summary.md"
printf '| Class | Result | Time |\n|---|---|---|\n' > "$summary"
for cls in $classes; do
  log="$out/${cls//#/_}.txt"
  echo "::group::$cls"
  start=$(date +%s)
  timeout --kill-after=10 "$limit" adb shell am instrument -w -r -e class "$pkg.$cls" "$runner" > "$log" 2>&1
  code=$?
  took=$(( $(date +%s) - start ))
  if (( code == 124 || code == 137 )); then
    result=TIMEOUT; hung+=("$cls")
    pid=$(adb shell pidof $pkg 2>/dev/null | tr -d '\r')
    [[ -n "$pid" ]] && adb shell debuggerd -j "$pid" > "$out/${cls//#/_}-stacks.txt" 2>&1
    adb exec-out screencap -p > "$out/${cls//#/_}-screen.png" 2>/dev/null
    adb shell dumpsys activity activities > "$out/${cls//#/_}-activities.txt" 2>&1
    adb shell am force-stop $pkg
  # `am instrument` exits 0 even when tests fail, so read its report.
  elif grep -q '^OK (' "$log" && ! grep -q 'FAILURES!!!\|INSTRUMENTATION_FAILED\|Process crashed\|UI_STATE_RESTORE_FAILED' "$log"; then
    result=PASS; passed+=("$cls")
  else
    result=FAIL; failed+=("$cls")
  fi
  tail -n 40 "$log"
  echo "::endgroup::"
  echo "$cls: $result (${took}s)"
  [[ $result == PASS ]] || echo "::warning::$cls $result on the emulator"
  printf '| %s | %s | %ss |\n' "$cls" "$result" "$took" >> "$summary"
done

printf '\n%d passed, %d failed, %d timed out (limit %ss)\n' ${#passed[@]} ${#failed[@]} ${#hung[@]} "$limit" | tee -a "$summary"
[[ -n "${GITHUB_STEP_SUMMARY:-}" ]] && { echo "## Device tests"; cat "$summary"; } >> "$GITHUB_STEP_SUMMARY"

if (( ${#failed[@]} + ${#hung[@]} > 0 )); then
  if [[ "${DEVICE_TEST_REPORT_ONLY:-0}" == 1 ]]; then
    echo "Report only: not failing the job"
  else
    echo "::error::Device tests failed: ${failed[*]} ${hung[*]}; logs, stacks and screenshots are in the device-tests artifact"
    exit 1
  fi
fi
