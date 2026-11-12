#!/usr/bin/env python3
"""Stores a model's convolution weights in float16 (docs/adr/0021 "M4/C", "Delivery and size"): TFLite's float16
weight quantization, done reproducibly with the standard library so the result can be pinned by checksum.

Every constant float32 filter of a CONV_2D, DEPTHWISE_CONV_2D or TRANSPOSE_CONV becomes a float16 constant read
through a DEQUANTIZE op (the layout TensorFlow's converter writes for `target_spec.supported_types = [tf.float16]`).
The GPU already computes in half precision, so its result is the same; the CPU (XNNPACK) dequantizes the weights
once when the model is compiled. Biases and every other tensor stay float32. Rounding: IEEE round-to-nearest-even
(Python's struct "e"), values beyond the half range are refused.

  convert_fp16_weights.py IN OUT [--report]
"""
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import tflite_flatbuffer as fb  # noqa: E402

# The filter's input index per operator.
FILTERS = {fb.CONV_2D: 1, fb.DEPTHWISE_CONV_2D: 1, fb.TRANSPOSE_CONV: 1}
HALF_MAX = 65504.0


def _dequantize_code(model):
    for i, code in enumerate(model["operator_codes"]):
        if max(code.get("builtin_code", 0), code.get("deprecated_builtin_code", 0)) == fb.DEQUANTIZE:
            return i
    model["operator_codes"].append({"deprecated_builtin_code": fb.DEQUANTIZE, "version": 1,
                                    "builtin_code": fb.DEQUANTIZE})
    return len(model["operator_codes"]) - 1


def convert(data):
    model = fb.read_model(data)
    if len(model["subgraphs"]) != 1:
        raise fb.FormatError("one subgraph expected")
    sg = model["subgraphs"][0]
    tensors = sg["tensors"]
    # Only weights every consumer reads as a convolution filter are halved (another op would lose its data).
    filter_uses, other_uses = set(), set()
    for op in sg["operators"]:
        index = FILTERS.get(fb.opcode(model, op))
        for k, t in enumerate(op["inputs"]):
            (filter_uses if k == index else other_uses).add(t)
    halved = {}  # float32 tensor -> its dequantized replacement
    out_ops = []
    converted = 0
    for op in sg["operators"]:
        index = FILTERS.get(fb.opcode(model, op))
        if index is not None and index < len(op["inputs"]):
            t = op["inputs"][index]
            tensor = tensors[t]
            raw = model["buffers"][tensor.get("buffer", 0)].get("data", b"")
            if tensor.get("type", fb.FLOAT32) == fb.FLOAT32 and raw and t not in other_uses:
                if t not in halved:
                    values = struct.unpack(f"<{len(raw) // 4}f", raw)
                    if any(abs(v) > HALF_MAX for v in values):
                        raise fb.FormatError(f"{tensor.get('name')}: weights beyond the float16 range")
                    model["buffers"].append({"data": struct.pack(f"<{len(values)}e", *values)})
                    half = dict(tensor)
                    half["type"] = fb.FLOAT16
                    half["buffer"] = len(model["buffers"]) - 1
                    half["name"] = tensor.get("name", "") + "/fp16"
                    tensors.append(half)
                    deq = {"shape": list(tensor["shape"]), "type": fb.FLOAT32, "buffer": 0,
                           "name": tensor.get("name", "") + "/dequantized"}
                    tensors.append(deq)
                    halved[t] = len(tensors) - 1
                    out_ops.append({"opcode_index": _dequantize_code(model), "inputs": [len(tensors) - 2],
                                    "outputs": [len(tensors) - 1]})
                    # The float32 tensor keeps its index but loses its data (pruned below).
                    tensor["buffer"] = 0
                    converted += 1
                op["inputs"] = list(op["inputs"])
                op["inputs"][index] = halved[t]
        out_ops.append(op)
    if converted == 0:
        raise fb.FormatError("no float32 convolution weights found")
    sg["operators"] = out_ops
    # The float32 weights are no longer referenced: drop them with their buffers.
    fb.prune(model, sg)
    return fb.write_model(model)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if len(args) != 2:
        raise SystemExit(__doc__)
    with open(args[0], "rb") as f:
        data = f.read()
    out = convert(data)
    with open(args[1], "wb") as f:
        f.write(out)
    if "--report" in sys.argv:
        print("original ", len(data), fb.histogram(fb.read_model(data)))
        print("converted", len(out), fb.histogram(fb.read_model(out)))


if __name__ == "__main__":
    main()
