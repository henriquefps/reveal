#!/usr/bin/env bash
# Macrobenchmarks and the Baseline Profile (docs/adr/0001 "Baseline Profile", docs/adr/0006 "Measurements") on a
# device: the `:benchmark` module drives the bench app (dev.hfps.reveal.bench; never the release app dev.hfps.reveal).
#
#   ./gradlew :app:assembleBench :benchmark:assembleBench                 # measure
#   ./gradlew :app:assembleBenchProfile :benchmark:assembleBenchProfile   # profile
#   scripts/bench/macrobench.sh <serial> measure <app-bench.apk> <benchmark-bench.apk> <sample.arw> [class]
#   scripts/bench/macrobench.sh <serial> profile <app-benchProfile.apk> <benchmark-benchProfile.apk> <sample.arw> <out dir>
#
# measure: StartupBenchmark (cold start to the 10,000-photo library), LibraryScrollBenchmark (flings, frame times) and
# EditorOpenBenchmark (a 24 MP RAW), each without compilation and with the Baseline Profile; or one [class]. The first
# run makes the library (10,000 generated files, a few minutes). Prints the instrumentation's results.
# profile: BaselineProfileGenerator on the unminified twin; copies the profiles into <out dir> (the repo's
# app/src/main/baselineProfiles: baseline-prof.txt = start + editor, startup-prof.txt = start only).
# Sample: the public CC0 sony-zv-e10.arw (scripts/fetch-samples.sh), never a private photo. ADB=<adb path>; KEEP=1 keeps
# the apps installed. On a phone with a Dual App profile it installs for user 0 only.
set -euo pipefail

serial=${1:?serial}
mode=${2:?measure|profile}
app=${3:?app apk}
tests=${4:?benchmark apk}
sample=${5:?sample}
adb=${ADB:-adb}
pkg=dev.hfps.reveal.bench
runner=dev.hfps.reveal.benchmark/androidx.test.runner.AndroidJUnitRunner
a() { "$adb" -s "$serial" "$@"; }
# The Mac has no `timeout`: perl's alarm bounds every long call (a dead device must not hang the run).
bounded() { perl -e 'alarm shift; exec @ARGV' "$@"; }

# Bounded: an install that waits on a prompt on the device must not hold the device lock for long.
bounded 300 "$adb" -s "$serial" install --user 0 -r "$app" >/dev/null
bounded 300 "$adb" -s "$serial" install --user 0 -r -t "$tests" >/dev/null
a shell svc power stayon usb
# The app makes its samples folder (one adb creates is not the app's), then adb fills it.
a logcat -c
a shell am start -n $pkg/dev.hfps.reveal.bench.BenchActivity --es mode init >/dev/null
sleep 3
a push "$sample" "/sdcard/Android/data/$pkg/files/samples/" >/dev/null
out=/sdcard/Android/media/dev.hfps.reveal.benchmark/additional_test_output

case "$mode" in
measure)
  classes=${6:-dev.hfps.reveal.benchmark.StartupBenchmark,dev.hfps.reveal.benchmark.LibraryScrollBenchmark,dev.hfps.reveal.benchmark.EditorOpenBenchmark}
  bounded 5400 "$adb" -s "$serial" shell am instrument -w -e class "$classes" \
    -e androidx.benchmark.suppressErrors EMULATOR "$runner" |
    awk '/android.studio.display.benchmark=/ { p = 1; sub(/^.*display.benchmark=/, "") } /Traces:/ { p = 0 } p; /^(OK|FAILURES|Tests run|Time:)/' || true
  a logcat -d -s REVEAL_BENCH | grep -E "editor open|open:" | sed 's/^.*REVEAL_BENCH: //' || true
  ;;
profile)
  dest=${6:?out dir}
  a shell rm -rf "$out"
  bounded 3600 "$adb" -s "$serial" shell am instrument -w -e class dev.hfps.reveal.benchmark.BaselineProfileGenerator \
    -e additionalTestOutputDir "$out" "$runner" | tail -5
  tmp=$(mktemp -d)
  a pull "$out" "$tmp" >/dev/null
  # BaselineProfileRule writes <class>_<test>-baseline-prof.txt and, for the startup test, -startup-prof.txt (which
  # holds that test's whole profile); the files without a date suffix are the latest.
  startup=$(find "$tmp" -name 'BaselineProfileGenerator_startup-startup-prof.txt' | head -1)
  editor=$(find "$tmp" -name 'BaselineProfileGenerator_editor-baseline-prof.txt' | head -1)
  [[ -n "$startup" && -n "$editor" ]] || { echo "profiles missing in $tmp" >&2; ls -R "$tmp" >&2; exit 1; }
  others=$(find "$tmp" -name 'BaselineProfileGenerator_startup-baseline-prof.txt')
  mkdir -p "$dest"
  # Sorted and unique, so a regeneration diffs cleanly.
  # awk 1: a file without a final newline must not run into the next one.
  awk 1 "$startup" "$editor" $others | sort -u >"$dest/baseline-prof.txt"
  sort -u "$startup" >"$dest/startup-prof.txt"
  wc -l "$dest/baseline-prof.txt" "$dest/startup-prof.txt"
  rm -rf "$tmp"
  ;;
*) echo "mode: measure or profile" >&2; exit 2 ;;
esac

a shell am start -n $pkg/dev.hfps.reveal.bench.BenchActivity --es mode cleanup >/dev/null || true
sleep 2
if [[ "${KEEP:-0}" != 1 ]]; then
  a uninstall dev.hfps.reveal.benchmark >/dev/null || true
  a uninstall $pkg >/dev/null && echo "uninstalled $pkg"
fi
