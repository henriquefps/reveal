#!/usr/bin/env bash
# Builds (once) and runs the host RAW dump used by the Camera JPEG match calibration (docs/adr/0008).
# Usage: scripts/calibration/rawdump.sh <in.ARW> <out.rdump> [full]   (full: whole-sensor AHD decode, docs/adr/0012)
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
libraw="$root/core/image/src/main/cpp/third_party/LibRaw"
out="$root/build/calibration"
bin="$out/rawdump"
if [[ ! -x "$bin" || "$here/rawdump.cpp" -nt "$bin" ]]; then
  mkdir -p "$out/obj"
  srcs=$(sed -n '/set(LIBRAW_SOURCES/,/^)/p' "$root/core/image/src/main/cpp/CMakeLists.txt" | grep -o 'src/[^ )]*\.cpp')
  objs=()
  for s in $srcs; do
    o="$out/obj/$(echo "$s" | tr '/' '_').o"
    if [[ ! -f "$o" ]]; then
      # At most 4 compilers at a time (the build hosts are also desktops).
      while (( $(jobs -rp | wc -l) >= 4 )); do wait -n; done
      c++ -std=c++17 -O3 -w -DLIBRAW_NODLL -DLIBRAW_NOTHREADS -I"$libraw" -c "$libraw/$s" -o "$o" &
    fi
    objs+=("$o")
  done
  wait
  c++ -std=c++17 -O2 -DLIBRAW_NODLL -I"$libraw" "$here/rawdump.cpp" "${objs[@]}" -o "$bin"
fi
"$bin" "$@"
