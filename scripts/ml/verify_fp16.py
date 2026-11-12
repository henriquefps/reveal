#!/usr/bin/env python3
# /// script
# requires-python = ">=3.10,<3.13"
# dependencies = ["ai-edge-litert==2.3.0", "numpy==2.5.4", "pillow==13.0.0"]
# ///
"""Host check of a float16-weights conversion (convert_fp16_weights.py, docs/adr/0021 "M4/C"): Selfie Multiclass
original vs converted on LiteRT's CPU, on public images. Per class (background, hair, body skin, face skin, clothes,
others: the softmax of the 6 logits at 256 x 256): mean absolute difference and the IoU of the masks at 0.5 (classes
covering under 0.5 % of an image are skipped for the IoU). Fails when an IoU is under 0.99 or a mean difference over
0.01.

  uv run scripts/ml/verify_fp16.py ORIGINAL.tflite CONVERTED.tflite IMAGE...
"""
import sys

import numpy as np
from ai_edge_litert.interpreter import Interpreter
from PIL import Image

CLASSES = ["background", "hair", "body-skin", "face-skin", "clothes", "others"]


def runner(path):
    it = Interpreter(model_path=path, num_threads=4)
    it.allocate_tensors()
    inp = it.get_input_details()[0]
    out = it.get_output_details()[0]["index"]

    def run(x):
        it.set_tensor(inp["index"], x)
        it.invoke()
        logits = it.get_tensor(out)[0].astype(np.float64)
        e = np.exp(logits - logits.max(axis=-1, keepdims=True))
        return e / e.sum(axis=-1, keepdims=True)

    return run, inp["shape"]


def main():
    if len(sys.argv) < 4:
        raise SystemExit(__doc__)
    (a, shape), (b, _) = runner(sys.argv[1]), runner(sys.argv[2])
    side = int(shape[1])
    worst_iou, worst_mad = 1.0, 0.0
    for path in sys.argv[3:]:
        img = Image.open(path).convert("RGB").resize((side, side), Image.BILINEAR)
        x = (np.asarray(img, np.float32) / 255.0 * 2.0 - 1.0)[None]
        pa, pb = a(x), b(x)
        parts = []
        for c, name in enumerate(CLASSES):
            mad = float(np.abs(pa[..., c] - pb[..., c]).mean())
            ma, mb = pa[..., c] >= 0.5, pb[..., c] >= 0.5
            union = int((ma | mb).sum())
            iou = float((ma & mb).sum()) / union if union > 0.005 * ma.size else 1.0
            worst_iou, worst_mad = min(worst_iou, iou), max(worst_mad, mad)
            parts.append(f"{name} {mad:.5f}/{iou:.4f}")
        print(f"FP16 {path.rsplit('/', 1)[-1]}: " + ", ".join(parts))
    print(f"FP16 worst IoU {worst_iou:.5f}, worst mean abs diff {worst_mad:.5f}")
    if worst_iou < 0.99 or worst_mad > 0.01:
        raise SystemExit("float16 model differs from the original")


if __name__ == "__main__":
    main()
