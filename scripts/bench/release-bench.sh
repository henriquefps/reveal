#!/usr/bin/env bash
# Release-build measurements on a device (docs/adr/0019 and 0020 "Measurements"): the `bench` build type (minified
# like release, app id dev.hfps.reveal.bench, debug-signed, profileable; never the release app dev.hfps.reveal).
#
#   ./gradlew :app:assembleBench
#   scripts/bench/release-bench.sh <serial> app/build/outputs/apk/bench/app-bench.apk <sample> [<sample> ...]
#
# Samples: public CC0 RAWs (scripts/fetch-samples.sh) and the public-domain test portrait
# (app/src/androidTest/assets/faces/studio-trio.jpg); never private photos. The first .arw is opened in the editor
# for the preset grid scroll. Prints the REVEAL_BENCH lines (face analysis per photo), the editor's own analysis time
# of the open (RevealEditor "features:") and `dumpsys gfxinfo` for 20 swipes of the preset grid. Uninstalls the bench
# app at the end unless KEEP=1; COMPILE=speed compiles it ahead of time first. On a phone with a Dual App profile it installs for user 0 only.
set -euo pipefail

serial=${1:?serial}
apk=${2:?bench apk}
shift 2
adb=${ADB:-adb}
pkg=dev.hfps.reveal.bench
activity=$pkg/dev.hfps.reveal.bench.BenchActivity
a() { "$adb" -s "$serial" "$@"; }

wait_log() { # tag pattern timeout_s
  local end=$((SECONDS + $3))
  while ((SECONDS < end)); do
    if a logcat -d -s "$1" | grep -q "$2"; then return 0; fi
    sleep 2
  done
  echo "timed out waiting for '$2'" >&2
  return 1
}

a install --user 0 -r "$apk" >/dev/null
# COMPILE=speed: compile ahead of time first (what a profile-guided Play install approaches); default: as adb installs
# it (JIT, measured after a warm-up run).
if [[ -n "${COMPILE:-}" ]]; then a shell cmd package compile -m "$COMPILE" -f $pkg >/dev/null; echo "compiled: $COMPILE"; fi
a shell svc power stayon usb
dir=/sdcard/Android/data/$pkg/files/samples
# The app makes the folder (one adb creates is not readable by the app), then adb fills it.
a shell rm -rf "/sdcard/Android/data/$pkg"
a logcat -c
a shell am start -n $activity --es mode init >/dev/null
wait_log REVEAL_BENCH "init:" 60
for f in "$@"; do a push "$f" "$dir/" >/dev/null; done

echo "== face analysis (minified)"
a shell am force-stop $pkg
a logcat -c
a shell am start -n $activity --es mode faces --ei runs "${RUNS:-5}" >/dev/null
wait_log REVEAL_BENCH "faces: done" 900
a logcat -d -s REVEAL_BENCH | grep "faces:" | sed 's/^.*REVEAL_BENCH: //'

echo "== preset grid scroll (minified)"
a shell am force-stop $pkg
a logcat -c
a shell am start -n $activity --es mode presets >/dev/null
wait_log REVEAL_BENCH "presets: grid" 180
read -r left top right bottom < <(a logcat -d -s REVEAL_BENCH | grep "presets: grid" | tail -1 | sed 's/.*grid //')
x=$(((left + right) / 2))
h=$((bottom - top))
y1=$((top + h * 15 / 100))
y2=$((bottom - h * 15 / 100))
sleep 3
a shell dumpsys gfxinfo $pkg reset >/dev/null
for i in $(seq 1 20); do
  if ((i <= 10)); then a shell input touchscreen swipe $x $y2 $x $y1 250; else a shell input touchscreen swipe $x $y1 $x $y2 250; fi
  sleep 0.6
done
sleep 1.5
a shell dumpsys gfxinfo $pkg | grep -E "Total frames|Janky frames|percentile|Number (Missed|Slow|Frame deadline)|Refresh" | head -20
a logcat -d -s RevealEditor | grep -E "features:" | sed 's/^.*RevealEditor: /editor open, /' || true
a shell dumpsys display | grep -m1 -oE "renderFrameRate [0-9.]+" || true

a shell am force-stop $pkg
a shell am start -n $activity --es mode cleanup >/dev/null
wait_log REVEAL_BENCH "cleanup: done" 60 || true
if [[ "${KEEP:-0}" != 1 ]]; then a uninstall $pkg >/dev/null && echo "uninstalled $pkg"; fi
