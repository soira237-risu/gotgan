#!/usr/bin/env bash
# Temporary diagnosis: does the app keep drawing frames on its own, outside instrumentation?
set -uo pipefail
cd "$(dirname "$0")/../.."
pkg=com.quickfiles.app
out=app/build/device-probe; mkdir -p "$out"
adb install -r -t -g app/build/outputs/apk/debug/app-debug.apk
adb shell appops set $pkg MANAGE_EXTERNAL_STORAGE allow
adb shell am start -W -n $pkg/.MainActivity | tee "$out/start.txt"
sleep 3
adb exec-out screencap -p > "$out/screen-3s.png"
sleep 12
adb shell dumpsys gfxinfo $pkg | grep -E "Total frames|Janky" | tee "$out/gfx1.txt"
sleep 10
adb shell dumpsys gfxinfo $pkg | grep -E "Total frames|Janky" | tee "$out/gfx2.txt"
adb exec-out screencap -p > "$out/screen-25s.png"
adb shell dumpsys activity top > "$out/top.txt"
grep -A3 "Looper (main" "$out/top.txt"; grep "mTraversalScheduled\|mFrameScheduled\|mLastFrameTime" "$out/top.txt"
adb shell perfetto -o /data/misc/perfetto-traces/probe.pftrace -t 4s -b 64mb -a $pkg sched gfx view am wm input dalvik
adb pull /data/misc/perfetto-traces/probe.pftrace "$out/probe.pftrace"
adb shell am force-stop $pkg
