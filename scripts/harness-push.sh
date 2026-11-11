#!/usr/bin/env bash
# Copies sample files into the :core:image test app's private storage on an EMULATOR (never the phone):
#   scripts/harness-push.sh emulator-5561 core/image/build/samples/*        (public samples)
#   scripts/harness-push.sh emulator-5561 --clean                           (delete what was pushed)
# The instrumented harness reads files/samples of dev.hfps.image.test (see Harness.kt).
set -euo pipefail

serial="${1:?emulator serial, e.g. emulator-5561}"
shift
case "$serial" in emulator-*) ;; *) echo "harness-push.sh: only emulators ($serial)" >&2; exit 1 ;; esac
adb="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}/platform-tools/adb -s $serial"
pkg="dev.hfps.image.test"
stage="/data/local/tmp/reveal-harness"

if [ "${1:-}" = "--clean" ]; then
  $adb shell "run-as $pkg rm -rf files/samples" || true
  $adb shell "rm -rf $stage"
  echo "cleaned"
  exit 0
fi

$adb shell "rm -rf $stage && mkdir -p $stage"
for f in "$@"; do $adb push "$f" "$stage/" >/dev/null; done
$adb shell "chmod -R a+rX $stage && run-as $pkg sh -c 'mkdir -p files/samples && cp $stage/* files/samples/'"
$adb shell "rm -rf $stage"
$adb shell "run-as $pkg ls -la files/samples"
