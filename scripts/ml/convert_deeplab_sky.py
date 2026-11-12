#!/usr/bin/env python3
"""Converts DeepLab-v3 MobileNetV2 ADE20K (deeplab-v3-ade20k@1) into Reveal's sky model (docs/adr/0021 "M4/C").

Two rewrites, both exact up to float rounding, so the converted file can be regenerated and pinned by checksum:

1. Every SPACE_TO_BATCH_ND -> (DEPTHWISE_)CONV_2D (VALID) -> BATCH_TO_SPACE_ND triple (TensorFlow's lowering of an
   atrous convolution) becomes the convolution itself with dilation = the block size and SAME padding. LiteRT's GPU
   delegate has no SPACE_TO_BATCH_ND / BATCH_TO_SPACE_ND, which left 91 of 135 ops of the original on the CPU.
   The rewrite is only applied when it is provably the same computation: stride 1, odd kernel, the paddings before
   minus the crops before equal to dilation * (kernel - 1) / 2 on both axes, and the block output the size of the
   block input.
2. The 151-class logits (513 x 513 x 151 after the upsampling, 159 MB of float readback per run) are replaced by one
   channel, the sky logit against the log-sum-exp of every other class, computed at the logits' own resolution and
   then upsampled like the original:

       D_c = L_c - L_sky                                (a 1 x 1 convolution, 151 -> 151)
       s   = sum_{c != sky} exp(min(D_c, CLAMP)) + EPS  (MINIMUM, EXP, a 1 x 1 convolution 151 -> 1 with bias EPS)
       z   = -log(s)                                    (LOG, MUL by -1) = L_sky - logsumexp_{c != sky} L_c
       sky = sigmoid(z)                                 (the app decodes it: Activation.SIGMOID)

   sigmoid(z) is exactly the softmax probability of the sky class. CLAMP keeps the sum of 150 exponentials inside
   float16 (the GPU may run in half precision: 150 * e^6 < 65504); it only bites where another class beats the sky by
   more than CLAMP, i.e. where sky < e^-6 = 0.0025. EPS keeps log finite where the sky beats everything by far
   (sky <= 1 / (1 + EPS)). Every new op is on LiteRT's GPU op list (CONV_2D, MINIMUM, EXP, LOG, MUL,
   RESIZE_BILINEAR).

Unused tensors, buffers and operator codes are dropped. Standard library only (scripts/ml/tflite_flatbuffer.py).

  convert_deeplab_sky.py IN OUT          # convert (fetch_models.py does this for the build, from the pinned input)
  convert_deeplab_sky.py IN OUT --report # also print the op histogram of both graphs
"""
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import tflite_flatbuffer as fb  # noqa: E402

SKY = 3  # ADE20K class index of "sky" in the model's 151 outputs (0 = other)
CLAMP = 6.0
EPS = 1e-4
SAME, VALID = 0, 1


def _int32s(model, sg, tensor):
    t = sg["tensors"][tensor]
    data = model["buffers"][t["buffer"]].get("data", b"")
    if t.get("type", 0) != fb.INT32 or not data:
        raise fb.FormatError(f"tensor {t.get('name')} is not a constant int32 tensor")
    return list(struct.unpack(f"<{len(data) // 4}i", data))


def _opcode_index(model, builtin):
    for i, code in enumerate(model["operator_codes"]):
        if max(code.get("builtin_code", 0), code.get("deprecated_builtin_code", 0)) == builtin:
            return i
    model["operator_codes"].append({"deprecated_builtin_code": min(builtin, 127), "version": 1,
                                    "builtin_code": builtin})
    return len(model["operator_codes"]) - 1


def _const(model, sg, name, shape, values, fmt="f", ttype=fb.FLOAT32):
    model["buffers"].append({"data": struct.pack(f"<{len(values)}{fmt}", *values)})
    sg["tensors"].append({"shape": shape, "type": ttype, "buffer": len(model["buffers"]) - 1, "name": name})
    return len(sg["tensors"]) - 1


def _tensor(sg, name, shape):
    # Activations point at the empty buffer 0, like every runtime tensor the converter made.
    sg["tensors"].append({"shape": shape, "type": fb.FLOAT32, "buffer": 0, "name": name})
    return len(sg["tensors"]) - 1


def _op(model, builtin, inputs, outputs, options_type=0, options=None):
    op = {"opcode_index": _opcode_index(model, builtin), "inputs": inputs, "outputs": outputs}
    if options_type:
        op["builtin_options_type"] = options_type
        op["builtin_options"] = options or {}
    return op


def dilate(model, sg):
    """Rewrite 1: SPACE_TO_BATCH_ND / conv / BATCH_TO_SPACE_ND triples into dilated convolutions."""
    ops = sg["operators"]
    consumers = {}
    for i, op in enumerate(ops):
        for t in op.get("inputs", []):
            consumers.setdefault(t, []).append(i)
    drop = set()
    rewritten = 0
    for i, stb in enumerate(ops):
        if fb.opcode(model, stb) != fb.SPACE_TO_BATCH_ND:
            continue
        x, block_t, pad_t = stb["inputs"]
        (mid,) = stb["outputs"]
        users = consumers.get(mid, [])
        if len(users) != 1:
            raise fb.FormatError("SPACE_TO_BATCH_ND output used more than once")
        conv = ops[users[0]]
        if fb.opcode(model, conv) not in (fb.DEPTHWISE_CONV_2D, fb.CONV_2D):
            raise fb.FormatError("SPACE_TO_BATCH_ND not followed by a convolution")
        (batched,) = conv["outputs"]
        users = consumers.get(batched, [])
        if len(users) != 1 or fb.opcode(model, ops[users[0]]) != fb.BATCH_TO_SPACE_ND:
            raise fb.FormatError("convolution not followed by BATCH_TO_SPACE_ND")
        bts = ops[users[0]]
        out, crop_block_t, crop_t = bts["outputs"][0], bts["inputs"][1], bts["inputs"][2]
        block = _int32s(model, sg, block_t)
        if _int32s(model, sg, crop_block_t) != block or block[0] != block[1]:
            raise fb.FormatError(f"block shapes differ: {block}")
        pads = _int32s(model, sg, pad_t)
        crops = _int32s(model, sg, crop_t)
        o = conv["builtin_options"]
        if o.get("padding", SAME) != VALID or o.get("stride_w", 1) != 1 or o.get("stride_h", 1) != 1:
            raise fb.FormatError("only VALID stride-1 convolutions are rewritten")
        if o.get("dilation_w_factor", 1) != 1 or o.get("dilation_h_factor", 1) != 1:
            raise fb.FormatError("convolution already dilated")
        kshape = sg["tensors"][conv["inputs"][1]]["shape"]
        kh, kw = kshape[1], kshape[2]
        d = block[0]
        if kh % 2 == 0 or kw % 2 == 0:
            raise fb.FormatError("even kernels are not rewritten")
        if pads[0] - crops[0] != d * (kh - 1) // 2 or pads[2] - crops[2] != d * (kw - 1) // 2:
            raise fb.FormatError(f"paddings {pads} / crops {crops} are not SAME for dilation {d}")
        if sg["tensors"][x]["shape"][1:3] != sg["tensors"][out]["shape"][1:3]:
            raise fb.FormatError("block output size differs from its input")
        conv["inputs"] = [x] + conv["inputs"][1:]
        conv["outputs"] = [out]
        o["padding"] = SAME
        o["dilation_w_factor"] = d
        o["dilation_h_factor"] = d
        drop.add(i)
        drop.add(users[0])
        rewritten += 1
    sg["operators"] = [op for i, op in enumerate(ops) if i not in drop]
    return rewritten


def sky_head(model, sg):
    """Rewrite 2: the 151-class output becomes the sky logit against the log-sum-exp of the rest."""
    ops = sg["operators"]
    producer = {t: op for op in ops for t in op.get("outputs", [])}
    (out,) = sg["outputs"]
    resizes = []
    t = out
    while fb.opcode(model, producer[t]) == fb.RESIZE_BILINEAR:
        resizes.insert(0, producer[t])
        t = producer[t]["inputs"][0]
    logits = t
    _, h, w, classes = sg["tensors"][logits]["shape"]
    if classes <= SKY:
        raise fb.FormatError(f"logits have {classes} classes")

    diff_w = [(1.0 if o == i else 0.0) - (1.0 if i == SKY else 0.0) for o in range(classes) for i in range(classes)]
    k_diff = _const(model, sg, "reveal/sky_diff_weights", [classes, 1, 1, classes], diff_w)
    b_diff = _const(model, sg, "reveal/sky_diff_bias", [classes], [0.0] * classes)
    k_sum = _const(model, sg, "reveal/rest_sum_weights", [1, 1, 1, classes],
                   [0.0 if c == SKY else 1.0 for c in range(classes)])
    b_sum = _const(model, sg, "reveal/rest_sum_bias", [1], [EPS])
    clamp = _const(model, sg, "reveal/clamp", [], [CLAMP])
    minus_one = _const(model, sg, "reveal/minus_one", [], [-1.0])

    t_diff = _tensor(sg, "reveal/diff", [1, h, w, classes])
    t_clamped = _tensor(sg, "reveal/diff_clamped", [1, h, w, classes])
    t_exp = _tensor(sg, "reveal/exp", [1, h, w, classes])
    t_sum = _tensor(sg, "reveal/rest_sum", [1, h, w, 1])
    t_log = _tensor(sg, "reveal/log_rest_sum", [1, h, w, 1])
    t_z = _tensor(sg, "reveal/sky_logit_low", [1, h, w, 1])
    conv = {"padding": VALID, "stride_w": 1, "stride_h": 1, "fused_activation_function": 0}
    head = [
        _op(model, fb.CONV_2D, [logits, k_diff, b_diff], [t_diff], 1, dict(conv)),
        _op(model, fb.MINIMUM, [t_diff, clamp], [t_clamped], 39, {}),
        _op(model, fb.EXP, [t_clamped], [t_exp], 33, {}),
        _op(model, fb.CONV_2D, [t_exp, k_sum, b_sum], [t_sum], 1, dict(conv)),
        _op(model, fb.LOG, [t_sum], [t_log]),
        _op(model, fb.MUL, [t_log, minus_one], [t_z], 21, {"fused_activation_function": 0}),
    ]
    t = t_z
    for k, r in enumerate(resizes):
        oh, ow = sg["tensors"][r["outputs"][0]]["shape"][1:3]
        last = k == len(resizes) - 1
        nxt = _tensor(sg, "sky_logit" if last else f"reveal/sky_logit_up{k}", [1, oh, ow, 1])
        head.append(_op(model, fb.RESIZE_BILINEAR, [t, r["inputs"][1]], [nxt], 15, dict(r["builtin_options"])))
        t = nxt
    sg["operators"] = [op for op in ops if op not in resizes] + head
    sg["outputs"] = [t]


def convert(data):
    """The converted model's bytes from the original's."""
    model = fb.read_model(data)
    if len(model["subgraphs"]) != 1:
        raise fb.FormatError("one subgraph expected")
    sg = model["subgraphs"][0]
    if dilate(model, sg) == 0:
        raise fb.FormatError("no SPACE_TO_BATCH_ND pattern found: not the expected model")
    sky_head(model, sg)
    fb.prune(model, sg)
    model["description"] = "Reveal sky: DeepLab-v3 MobileNetV2 ADE20K, dilated convolutions, sky logit (ADR 0021)"
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
        print("original ", fb.histogram(fb.read_model(data)))
        print("converted", fb.histogram(fb.read_model(out)))


if __name__ == "__main__":
    main()
