#!/usr/bin/env python3
"""Stores a model's convolution weights as int8 (docs/adr/0024): weight-only quantization done reproducibly with the
standard library, so the result can be pinned by checksum like a download.

Every constant float32 filter of a CONV_2D or TRANSPOSE_CONV becomes an int8 constant with one scale per output
channel (symmetric: zero point 0, scale = max |w| / 127 over the channel, quantized dimension 0 = the output
channels of TFLite's [O, H, W, I] filters), read through a DEQUANTIZE op that restores float32 when the model is
compiled; the arithmetic stays floating point (the GPU computes in half precision, XNNPACK in float32). Rounding:
round half to even (Python's round), clamped to -127..127. Biases, the DFT tables of FULLY_CONNECTED/BATCH_MATMUL and
every other tensor stay float32. Filters smaller than MIN_ELEMENTS and filters over the image itself (at most
MAX_IMAGE_CHANNELS input channels: rgb + mask) stay float32: they cost almost nothing, and int8 on big-LaMa's first
convolution alone moved fills by 0.09 on average (every other group together: under 0.01).

  convert_int8_weights.py IN OUT [--report]
"""
import array
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import tflite_flatbuffer as fb  # noqa: E402

# The filter's input index per operator: CONV_2D (input, filter, bias); TRANSPOSE_CONV (output_shape, filter, input).
FILTERS = {fb.CONV_2D: 1, fb.TRANSPOSE_CONV: 1}
MIN_ELEMENTS = 4096
MAX_IMAGE_CHANNELS = 4


def _dequantize_code(model):
    for i, code in enumerate(model["operator_codes"]):
        if max(code.get("builtin_code", 0), code.get("deprecated_builtin_code", 0)) == fb.DEQUANTIZE:
            return i
    model["operator_codes"].append({"deprecated_builtin_code": fb.DEQUANTIZE, "version": 2,
                                    "builtin_code": fb.DEQUANTIZE})
    return len(model["operator_codes"]) - 1


def quantize(values, channels):
    """Per-channel symmetric int8 of a flat [channels, ...] float list: (int8 bytes, scales)."""
    per = len(values) // channels
    out = array.array("b", bytes(len(values)))
    scales = []
    for c in range(channels):
        chunk = values[c * per:(c + 1) * per]
        m = max((abs(v) for v in chunk), default=0.0)
        scale = m / 127.0 if m > 0 else 1.0
        scales.append(scale)
        for i, v in enumerate(chunk):
            q = round(v / scale)
            out[c * per + i] = -127 if q < -127 else 127 if q > 127 else q
    # scales are stored as float32: recompute nothing from the rounded value, the reader uses these exact numbers
    return out.tobytes(), [struct.unpack("<f", struct.pack("<f", s))[0] for s in scales]


def convert(data, select=lambda name: True):
    model = fb.read_model(data)
    if len(model["subgraphs"]) != 1:
        raise fb.FormatError("one subgraph expected")
    sg = model["subgraphs"][0]
    tensors = sg["tensors"]
    other_uses = set()
    for op in sg["operators"]:
        index = FILTERS.get(fb.opcode(model, op))
        for k, t in enumerate(op["inputs"]):
            if k != index:
                other_uses.add(t)
    done = {}
    out_ops = []
    for op in sg["operators"]:
        index = FILTERS.get(fb.opcode(model, op))
        if index is not None and index < len(op["inputs"]):
            t = op["inputs"][index]
            tensor = tensors[t]
            raw = model["buffers"][tensor.get("buffer", 0)].get("data", b"")
            big = len(raw) // 4 >= MIN_ELEMENTS
            # A filter reading the image itself (big-LaMa's first 7 x 7 convolution over rgb + mask) shares each
            # output channel's scale between inputs of very different ranges: int8 there turned whole fills green.
            image_input = len(tensor.get("shape", [])) == 4 and tensor["shape"][3] <= MAX_IMAGE_CHANNELS
            chosen = select(tensor.get("name", ""))
            eligible = big and chosen and not image_input and t not in other_uses
            if tensor.get("type", fb.FLOAT32) == fb.FLOAT32 and raw and eligible:
                if t not in done:
                    values = array.array("f")
                    values.frombytes(raw)
                    if sys.byteorder != "little":
                        values.byteswap()
                    q, scales = quantize(values, tensor["shape"][0])
                    model["buffers"].append({"data": q})
                    qt = {"shape": list(tensor["shape"]), "type": fb.INT8, "buffer": len(model["buffers"]) - 1,
                          "name": tensor.get("name", "") + "/int8",
                          "quantization": {"scale": scales, "zero_point": [0] * len(scales),
                                           "quantized_dimension": 0}}
                    tensors.append(qt)
                    tensors.append({"shape": list(tensor["shape"]), "type": fb.FLOAT32, "buffer": 0,
                                    "name": tensor.get("name", "") + "/dequantized"})
                    done[t] = len(tensors) - 1
                    out_ops.append({"opcode_index": _dequantize_code(model), "inputs": [len(tensors) - 2],
                                    "outputs": [len(tensors) - 1]})
                    tensor["buffer"] = 0
                op["inputs"] = list(op["inputs"])
                op["inputs"][index] = done[t]
        out_ops.append(op)
    if not done:
        raise fb.FormatError("no float32 convolution weights found")
    sg["operators"] = out_ops
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
