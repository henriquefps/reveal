#!/usr/bin/env bash
# Headless emulator helper for Reveal workers. Creates a named AVD without avdmanager (this Mac has no
# cmdline-tools), starts it on a given console port, waits for boot, stops and deletes it.
#
#   scripts/emulator.sh create <name>            AVD: arm64-v8a, API 36.1 google_apis_playstore, Pixel-like screen
#   scripts/emulator.sh start  <name> <port>     headless on emulator-<port> (5560-5590), waits until booted
#   scripts/emulator.sh stop   <port>            kill emulator-<port>
#   scripts/emulator.sh delete <name>            remove the AVD
#   scripts/emulator.sh serial <port>            prints emulator-<port>
#
# Rules: at most one emulator per worker, never touch the owner's phone (RXCX109VTTM), always target
# `adb -s emulator-<port>` / ANDROID_SERIAL, stop and delete when done.
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
ADB="$SDK/platform-tools/adb"
EMULATOR="$SDK/emulator/emulator"
IMAGE="system-images/android-36.1/google_apis_playstore/arm64-v8a/"
GPU="${EMULATOR_GPU:-swiftshader_indirect}"

die() { echo "emulator.sh: $*" >&2; exit 1; }

cmd="${1:-}"; shift || true
case "$cmd" in
  create)
    name="${1:?name}"
    [ -d "$AVD_HOME/$name.avd" ] && die "AVD $name already exists"
    mkdir -p "$AVD_HOME/$name.avd"
    cat > "$AVD_HOME/$name.ini" <<INI
avd.ini.encoding=UTF-8
path=$AVD_HOME/$name.avd
path.rel=avd/$name.avd
target=android-36.1
INI
    cat > "$AVD_HOME/$name.avd/config.ini" <<INI
AvdId=$name
avd.ini.displayname=$name
PlayStore.enabled=true
abi.type=arm64-v8a
hw.cpu.arch=arm64
hw.cpu.ncore=4
hw.ramSize=3072
disk.dataPartition.size=6G
hw.lcd.width=1080
hw.lcd.height=2400
hw.lcd.density=420
hw.keyboard=yes
hw.gpu.enabled=yes
hw.gpu.mode=auto
hw.sdCard=no
image.sysdir.1=$IMAGE
tag.id=google_apis_playstore
tag.display=Google Play
showDeviceFrame=no
INI
    echo "created $name"
    ;;
  start)
    name="${1:?name}"; port="${2:?port (5560-5590)}"
    [ "$port" -ge 5560 ] && [ "$port" -le 5590 ] || die "port must be 5560-5590"
    nohup "$EMULATOR" -avd "$name" -port "$port" -no-window -no-audio -no-boot-anim -no-snapshot -gpu "$GPU" \
      > "${TMPDIR:-/tmp}/emulator-$port.log" 2>&1 &
    serial="emulator-$port"
    "$ADB" -s "$serial" wait-for-device
    until [ "$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 3; done
    echo "$serial booted"
    ;;
  stop)
    port="${1:?port}"
    "$ADB" -s "emulator-$port" emu kill >/dev/null 2>&1 || true
    echo "stopped emulator-$port"
    ;;
  delete)
    name="${1:?name}"
    rm -rf "$AVD_HOME/$name.avd" "$AVD_HOME/$name.ini"
    echo "deleted $name"
    ;;
  serial)
    echo "emulator-${1:?port}"
    ;;
  *)
    sed -n '2,13p' "$0"; exit 2
    ;;
esac
