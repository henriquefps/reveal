#!/usr/bin/env bash
# Camera JPEG match calibration (docs/adr/0008, "Calibration"). Opt-in, on the owner's machine only.
#
#   scripts/calibration/calibrate.sh <work dir outside the repo> <folder of RAW+JPEG pairs> [more folders...]
#
# 1. COPIES every ARW+JPG pair into <work dir> (the originals are only read),
# 2. decodes each ARW with the vendored LibRaw and the app's own decode parameters (rawdump.sh),
# 3. runs CameraMatchCalibrationTest, which prints aggregates and the fitted constants (CALIB / CALIB-CONST lines),
# 4. deletes the copies and the dumps.
# Nothing from the photos is written into the repository; paste only the CALIB-CONST values into
# core/pipeline/src/main/kotlin/dev/hfps/reveal/pipeline/cpu/CameraMatchZvE10.kt.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
work="$1"; shift
case "$(cd "$work" 2>/dev/null && pwd || echo "$work")" in "$root"*) echo "work dir must be outside the repository" >&2; exit 2;; esac
mkdir -p "$work/pairs"
trap 'rm -rf "$work/pairs"' EXIT
for folder in "$@"; do
  for arw in "$folder"/*.ARW; do
    base="$(basename "$arw" .ARW)"
    [[ -f "$folder/$base.JPG" ]] || continue
    cp "$arw" "$folder/$base.JPG" "$work/pairs/"
  done
done
for arw in "$work"/pairs/*.ARW; do
  "$here/rawdump.sh" "$arw" "${arw%.ARW}.rdump" &
done
wait
rm -f "$work"/pairs/*.ARW
cd "$root"
REVEAL_CALIBRATION_DIR="$work/pairs" ./gradlew :core:pipeline:testDebugUnitTest \
  --tests '*CameraMatchCalibrationTest*' --rerun -i | grep -E '^ *CALIB'
