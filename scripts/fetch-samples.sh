#!/usr/bin/env bash
# Downloads the public-domain (CC0) sample RAWs used by the :core:image instrumented harness from raw.pixls.us into
# core/image/build/samples/ (git-ignored) and verifies their SHA-256. Nothing here is committed.
#
#   scripts/fetch-samples.sh            fetch what is missing
#   scripts/fetch-samples.sh --print    print "sha256  name" lines for the files present (to update the table below)
set -euo pipefail

cd "$(dirname "$0")/.."
DIR="core/image/build/samples"
BASE="https://raw.pixls.us/data"
mkdir -p "$DIR"

# name | path under $BASE | sha256 (empty = not yet recorded)
SAMPLES=(
  "sony-zv-e10.arw|Sony/ZV-E10/DSC00002.ARW|a8633fae9749ca8d2cf2212bda4c352553c24e7140660c1cd324b982e57ab42e"
  "google-pixel-4a.dng|Google/Pixel%204a/PXL_20201121_100251397.dng|11b38186ee3455376291bcdb3049728a3f9dcd94b9e82ff76855d9f3430e44be"
  "nikon-d5600.nef|Nikon/D5600/DSC_0027.NEF|82e15f02c4037820e80f14bacfacdc52ea29721b3c70d2cf08b237b50ce8db05"
  "canon-eos-r6.cr3|Canon/EOS%20R6/Canon_EOS_R6_RAW_ISO_100_nocrop_nodual.CR3|81fab349ae9080fd0172583b7fd5ea36806e95ffa434909d2ccc6527dde34ee6"
  # High ISO (Detail: noise reduction and sharpening judged at 100 %, docs/adr/0012). Sony A7C, 24 MP like the ZV-E10.
  "sony-a7c-iso12800.arw|Sony/ILCE-7C/DSC00107%5b1%5d.ARW|386ce0122687b802e8e57151d36643b830f87e59eed15b65c690d1032eb3b763"
  "sony-a7c-iso2000.arw|Sony/ILCE-7C/DSC00018.ARW|6bfac3286f0e931e949b23e1bb236b737bb9e4117b8b42fa09e36f40f0fa8408"
)

sha() { if command -v shasum >/dev/null; then shasum -a 256 "$1"; else sha256sum "$1"; fi | cut -d' ' -f1; }

if [ "${1:-}" = "--print" ]; then
  for entry in "${SAMPLES[@]}"; do
    IFS='|' read -r name _ _ <<<"$entry"
    [ -f "$DIR/$name" ] && echo "$(sha "$DIR/$name")  $name"
  done
  exit 0
fi

for entry in "${SAMPLES[@]}"; do
  IFS='|' read -r name path want <<<"$entry"
  target="$DIR/$name"
  if [ ! -f "$target" ]; then
    echo "fetching $name"
    curl -fsSL "$BASE/$path" -o "$target.part"
    mv "$target.part" "$target"
  fi
  got="$(sha "$target")"
  if [ -n "$want" ] && [ "$got" != "$want" ]; then
    echo "checksum mismatch for $name: $got (expected $want)" >&2
    rm -f "$target"
    exit 1
  fi
  echo "ok  $name  $got"
done
