#!/usr/bin/env python3
# /// script
# requires-python = ">=3.10,<3.13"
# dependencies = ["ai-edge-litert==2.3.0", "numpy==2.5.4", "pillow==13.0.0"]
# ///
"""Host check of the sky conversion (docs/adr/0021 "M4/C"): the converted model against the original on public
images, on LiteRT's CPU (XNNPACK). For each image: the original's sky probability (softmax of class 3 of the 151
logits at 513 x 513) and the converted one (sigmoid of its single logit); prints the mean absolute difference, the
IoU of the two masks at 0.5, and the run times. Fails when any IoU is under 0.99 or any mean difference over 0.01.

  uv run scripts/ml/verify_sky.py ORIGINAL.tflite CONVERTED.tflite IMAGE...

fetch_models.py prints the cached paths (`--paths`). Public images only (the repo's NASA fixtures).
"""
import sys
import time

import numpy as np
from ai_edge_litert.interpreter import Interpreter
from PIL import Image

SKY = 3
SIDE = 513


def load(path):
    img = Image.open(path).convert("RGB").resize((SIDE, SIDE), Image.BILINEAR)
    return (np.asarray(img, np.float32) / 255.0 * 2.0 - 1.0)[None]


def runner(path):
    it = Interpreter(model_path=path, num_threads=4)
    it.allocate_tensors()
    inp = it.get_input_details()[0]["index"]
    out = it.get_output_details()[0]["index"]

    def run(x):
        it.set_tensor(inp, x)
        t0 = time.perf_counter()
        it.invoke()
        ms = (time.perf_counter() - t0) * 1000
        return it.get_tensor(out).copy(), ms

    return run


def main():
    if len(sys.argv) < 4:
        raise SystemExit(__doc__)
    original, converted = runner(sys.argv[1]), runner(sys.argv[2])
    worst_iou, worst_mad = 1.0, 0.0
    times = [[], []]
    for path in sys.argv[3:]:
        x = load(path)
        logits, t0 = original(x)
        z, t1 = converted(x)
        logits = logits[0].astype(np.float64)
        e = np.exp(logits - logits.max(axis=-1, keepdims=True))
        p0 = e[..., SKY] / e.sum(axis=-1)
        p1 = 1.0 / (1.0 + np.exp(-z[0, ..., 0].astype(np.float64)))
        mad = float(np.abs(p0 - p1).mean())
        a, b = p0 >= 0.5, p1 >= 0.5
        union = int((a | b).sum())
        iou = 1.0 if union == 0 else float((a & b).sum()) / union
        worst_iou, worst_mad = min(worst_iou, iou), max(worst_mad, mad)
        times[0].append(t0)
        times[1].append(t1)
        print(f"SKY {path.rsplit('/', 1)[-1]}: sky {a.mean():.3f} vs {b.mean():.3f}, mean abs diff {mad:.5f}, "
              f"max {np.abs(p0 - p1).max():.4f}, IoU {iou:.5f}, CPU {t0:.0f} ms -> {t1:.0f} ms")
    print(f"SKY worst IoU {worst_iou:.5f}, worst mean abs diff {worst_mad:.5f}, median CPU "
          f"{np.median(times[0]):.0f} ms -> {np.median(times[1]):.0f} ms (4 threads, host)")
    if worst_iou < 0.99 or worst_mad > 0.01:
        raise SystemExit("converted sky model differs from the original")


if __name__ == "__main__":
    main()
