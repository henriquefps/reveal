"""A minimal, dependency-free reader and writer for the TFLite flatbuffer format (schema v3), enough to rewrite the
graphs Reveal converts (docs/adr/0021 "M4/C"). Standard library only, deterministic: the same input bytes always give
the same output bytes, so a converted model can be pinned by its SHA-256.

Only the tables and builtin-options types listed in SCHEMA / OPTIONS are understood; a model that uses anything else
(an unknown option type, a field this schema does not know, buffers stored outside the flatbuffer) is refused rather
than rewritten lossily. Decoded tables are plain dicts holding the fields present in the file; absent fields stay
absent when written back, so an unchanged model re-encodes to an equivalent file.
"""
import struct

# Field kinds: a struct format character for scalars, "str", "bytes" (a [ubyte] vector, 16-byte aligned like TFLite's
# buffers), "[x]" a vector of scalars x, "T:Name" a table, "[T:Name]" a vector of tables, "U:<type field>" a union of
# builtin options. Field order = the schema's field ids (a union takes two ids: its type, then its value).
SCHEMA = {
    "Model": [
        ("version", "I"), ("operator_codes", "[T:OperatorCode]"), ("subgraphs", "[T:SubGraph]"),
        ("description", "str"), ("buffers", "[T:Buffer]"), ("metadata_buffer", "[i]"),
        ("metadata", "[T:Metadata]"), ("signature_defs", "[T:SignatureDef]"),
    ],
    "OperatorCode": [("deprecated_builtin_code", "b"), ("custom_code", "str"), ("version", "i"), ("builtin_code", "i")],
    "SubGraph": [
        ("tensors", "[T:Tensor]"), ("inputs", "[i]"), ("outputs", "[i]"), ("operators", "[T:Operator]"),
        ("name", "str"),
    ],
    "Tensor": [
        ("shape", "[i]"), ("type", "b"), ("buffer", "I"), ("name", "str"), ("quantization", "T:Quantization"),
        ("is_variable", "B"), ("sparsity", "X"), ("shape_signature", "[i]"), ("has_rank", "B"),
        ("variant_tensors", "X"),
    ],
    "Quantization": [
        ("min", "[f]"), ("max", "[f]"), ("scale", "[f]"), ("zero_point", "[q]"), ("details_type", "B"),
        ("details", "X"), ("quantized_dimension", "i"),
    ],
    "Operator": [
        ("opcode_index", "I"), ("inputs", "[i]"), ("outputs", "[i]"), ("builtin_options_type", "B"),
        ("builtin_options", "U:builtin_options_type"), ("custom_options", "bytes"), ("custom_options_format", "b"),
        ("mutating_variable_inputs", "[B]"), ("intermediates", "[i]"), ("large_custom_options_offset", "X"),
        ("large_custom_options_size", "X"), ("builtin_options_2_type", "X"), ("builtin_options_2", "X"),
    ],
    "Buffer": [("data", "bytes"), ("offset", "X"), ("size", "X")],
    "Metadata": [("name", "str"), ("buffer", "I")],
    "SignatureDef": [
        ("inputs", "[T:TensorMap]"), ("outputs", "[T:TensorMap]"), ("signature_key", "str"),
        ("deprecated_tag", "str"), ("subgraph_index", "I"),
    ],
    "TensorMap": [("name", "str"), ("tensor_index", "I")],
}

# Builtin options (the BuiltinOptions union): type id -> (table name, fields). Only the types Reveal's converted
# graphs contain; the enum values and field ids are TFLite's schema.fbs.
OPTIONS = {
    1: ("Conv2DOptions", [
        ("padding", "b"), ("stride_w", "i"), ("stride_h", "i"), ("fused_activation_function", "b"),
        ("dilation_w_factor", "i"), ("dilation_h_factor", "i"), ("quantized_bias_type", "b"),
    ]),
    2: ("DepthwiseConv2DOptions", [
        ("padding", "b"), ("stride_w", "i"), ("stride_h", "i"), ("depth_multiplier", "i"),
        ("fused_activation_function", "b"), ("dilation_w_factor", "i"), ("dilation_h_factor", "i"),
    ]),
    8: ("FullyConnectedOptions", [
        ("fused_activation_function", "b"), ("weights_format", "b"), ("keep_num_dims", "B"),
        ("asymmetric_quantize_inputs", "B"), ("quantized_bias_type", "b"),
    ]),
    9: ("SoftmaxOptions", [("beta", "f")]),
    5: ("Pool2DOptions", [
        ("padding", "b"), ("stride_w", "i"), ("stride_h", "i"), ("filter_width", "i"), ("filter_height", "i"),
        ("fused_activation_function", "b"),
    ]),
    10: ("ConcatenationOptions", [("axis", "i"), ("fused_activation_function", "b")]),
    11: ("AddOptions", [("fused_activation_function", "b"), ("pot_scale_int16", "B")]),
    15: ("ResizeBilinearOptions", [
        ("new_height", "i"), ("new_width", "i"), ("align_corners", "B"), ("half_pixel_centers", "B"),
    ]),
    17: ("ReshapeOptions", [("new_shape", "[i]")]),
    21: ("MulOptions", [("fused_activation_function", "b")]),
    24: ("BatchToSpaceNDOptions", []),
    25: ("SpaceToBatchNDOptions", []),
    26: ("TransposeOptions", []),
    27: ("ReducerOptions", [("keep_dims", "B")]),
    28: ("SubOptions", [("fused_activation_function", "b"), ("pot_scale_int16", "B")]),
    33: ("ExpOptions", []),
    38: ("DequantizeOptions", []),
    39: ("MaximumMinimumOptions", []),
    48: ("SliceOptions", []),
    49: ("TransposeConvOptions", [
        ("padding", "b"), ("stride_w", "i"), ("stride_h", "i"), ("fused_activation_function", "b"),
        ("quantized_bias_type", "b"),
    ]),
    74: ("ResizeNearestNeighborOptions", [("align_corners", "B"), ("half_pixel_centers", "B")]),
    77: ("MirrorPadOptions", [("mode", "b")]),
    101: ("BatchMatMulOptions", [("adj_x", "B"), ("adj_y", "B"), ("asymmetric_quantize_inputs", "B")]),
}

# BuiltinOperator codes used here (schema.fbs).
ADD, AVERAGE_POOL_2D, CONCATENATION, CONV_2D, DEPTHWISE_CONV_2D, DEQUANTIZE = 0, 1, 2, 3, 4, 6
LOGISTIC, MUL, RESHAPE, RESIZE_BILINEAR, SOFTMAX, TRANSPOSE, TRANSPOSE_CONV = 14, 18, 22, 23, 25, 39, 67
RESIZE_NEAREST_NEIGHBOR = 97
FULLY_CONNECTED, SLICE, MIRROR_PAD, BATCH_MATMUL = 9, 65, 100, 126
BATCH_TO_SPACE_ND, SPACE_TO_BATCH_ND, MEAN, SUB, EXP, MINIMUM, LOG, SUM, REDUCE_MAX = 37, 38, 40, 41, 47, 57, 73, 74, 82
OP_NAMES = {
    ADD: "ADD", AVERAGE_POOL_2D: "AVERAGE_POOL_2D", CONCATENATION: "CONCATENATION", CONV_2D: "CONV_2D",
    DEPTHWISE_CONV_2D: "DEPTHWISE_CONV_2D", DEQUANTIZE: "DEQUANTIZE", LOGISTIC: "LOGISTIC", MUL: "MUL",
    RESHAPE: "RESHAPE", RESIZE_BILINEAR: "RESIZE_BILINEAR", BATCH_TO_SPACE_ND: "BATCH_TO_SPACE_ND",
    SPACE_TO_BATCH_ND: "SPACE_TO_BATCH_ND", MEAN: "MEAN", SUB: "SUB", EXP: "EXP", LOG: "LOG", SUM: "SUM",
    REDUCE_MAX: "REDUCE_MAX", MINIMUM: "MINIMUM", SOFTMAX: "SOFTMAX", TRANSPOSE: "TRANSPOSE",
    TRANSPOSE_CONV: "TRANSPOSE_CONV", RESIZE_NEAREST_NEIGHBOR: "RESIZE_NEAREST_NEIGHBOR",
    FULLY_CONNECTED: "FULLY_CONNECTED", SLICE: "SLICE", MIRROR_PAD: "MIRROR_PAD", BATCH_MATMUL: "BATCH_MATMUL",
}
# The options type each operator carries (0 = none). A model whose operator says otherwise is refused.
OP_OPTIONS = {
    ADD: 11, AVERAGE_POOL_2D: 5, CONCATENATION: 10, CONV_2D: 1, DEPTHWISE_CONV_2D: 2, DEQUANTIZE: 0, LOGISTIC: 0,
    MUL: 21, RESHAPE: 17, RESIZE_BILINEAR: 15, BATCH_TO_SPACE_ND: 24, SPACE_TO_BATCH_ND: 25, MEAN: 27, SUB: 28,
    EXP: 33, LOG: 0, SUM: 27, REDUCE_MAX: 27, MINIMUM: 39, SOFTMAX: 9, TRANSPOSE: 26, TRANSPOSE_CONV: 49,
    RESIZE_NEAREST_NEIGHBOR: 74, FULLY_CONNECTED: 8, SLICE: 48, MIRROR_PAD: 77, BATCH_MATMUL: 101,
}

# TensorType values.
FLOAT32, FLOAT16, INT32, INT8 = 0, 1, 2, 9

_SCALAR = {c: struct.calcsize("<" + c) for c in "bBhHiIqQfd"}


class FormatError(Exception):
    pass


# ---------------------------------------------------------------------------------------------------------- reading
def _u32(b, o):
    return struct.unpack_from("<I", b, o)[0]


def _table(b, pos, fields, name):
    vt = pos - struct.unpack_from("<i", b, pos)[0]
    vt_len = struct.unpack_from("<H", b, vt)[0]
    slots = (vt_len - 4) // 2
    out = {}
    for slot in range(slots):
        off = struct.unpack_from("<H", b, vt + 4 + 2 * slot)[0]
        if off == 0:
            continue
        if slot >= len(fields):
            raise FormatError(f"{name}: field {slot} is not in this schema")
        fname, kind = fields[slot]
        loc = pos + off
        if kind == "X":
            raise FormatError(f"{name}.{fname} is set: not supported by this converter")
        out[fname] = _value(b, loc, kind, out, name)
    return out


def _value(b, loc, kind, parent, name):
    if kind in _SCALAR:
        return struct.unpack_from("<" + kind, b, loc)[0]
    target = loc + _u32(b, loc)
    if kind == "str":
        n = _u32(b, target)
        return bytes(b[target + 4:target + 4 + n]).decode("utf-8")
    if kind == "bytes":
        n = _u32(b, target)
        return bytes(b[target + 4:target + 4 + n])
    if kind.startswith("T:"):
        sub = kind[2:]
        return _table(b, target, SCHEMA[sub], sub)
    if kind.startswith("U:"):
        utype = parent.get(kind[2:], 0)
        if utype not in OPTIONS:
            raise FormatError(f"{name}: builtin options type {utype} not supported")
        oname, ofields = OPTIONS[utype]
        return _table(b, target, ofields, oname)
    if kind.startswith("["):
        inner = kind[1:-1]
        n = _u32(b, target)
        base = target + 4
        if inner in _SCALAR:
            size = _SCALAR[inner]
            return list(struct.unpack_from(f"<{n}{inner}", b, base)) if n else []
        if inner.startswith("T:"):
            sub = inner[2:]
            return [_table(b, base + 4 * i + _u32(b, base + 4 * i), SCHEMA[sub], sub) for i in range(n)]
    raise FormatError(f"unknown field kind {kind}")


def read_model(data):
    """Decodes a .tflite file into dicts (see SCHEMA). Refuses anything this module could not write back."""
    if data[4:8] != b"TFL3":
        raise FormatError("not a TFLite flatbuffer (identifier TFL3 missing)")
    model = _table(data, _u32(data, 0), SCHEMA["Model"], "Model")
    for sg in model.get("subgraphs", []):
        for op in sg.get("operators", []):
            code = model["operator_codes"][op.get("opcode_index", 0)]
            builtin = max(code.get("builtin_code", 0), code.get("deprecated_builtin_code", 0))
            expected = OP_OPTIONS.get(builtin)
            if expected is None:
                raise FormatError(f"operator {builtin} is not supported by this converter")
            if op.get("builtin_options_type", 0) not in (0, expected):
                raise FormatError(f"operator {builtin} carries options type {op.get('builtin_options_type')}")
    return model


# ---------------------------------------------------------------------------------------------------------- editing
def opcode(model, op):
    """The builtin operator code of [op]."""
    code = model["operator_codes"][op.get("opcode_index", 0)]
    return max(code.get("builtin_code", 0), code.get("deprecated_builtin_code", 0))


def prune(model, sg):
    """Drops operators, tensors, buffers and operator codes that no longer reach the output."""
    needed = set(sg["outputs"])
    live_ops = []
    for op in reversed(sg["operators"]):
        if any(t in needed for t in op["outputs"]):
            live_ops.append(op)
            needed.update(t for t in op["inputs"] if t >= 0)
    live_ops.reverse()
    needed.update(sg["inputs"])
    tensors = sorted(needed)
    t_map = {old: new for new, old in enumerate(tensors)}
    keep_buffers = sorted({0} | {sg["tensors"][t].get("buffer", 0) for t in tensors}
                          | {m["buffer"] for m in model.get("metadata", [])}
                          | set(model.get("metadata_buffer", [])))
    b_map = {old: new for new, old in enumerate(keep_buffers)}
    codes = sorted({op.get("opcode_index", 0) for op in live_ops})
    c_map = {old: new for new, old in enumerate(codes)}

    new_tensors = []
    for t in tensors:
        tensor = dict(sg["tensors"][t])
        tensor["buffer"] = b_map[tensor.get("buffer", 0)]
        new_tensors.append(tensor)
    for op in live_ops:
        op["inputs"] = [t_map[t] if t >= 0 else t for t in op["inputs"]]
        op["outputs"] = [t_map[t] for t in op["outputs"]]
        op["opcode_index"] = c_map[op.get("opcode_index", 0)]
    sg["tensors"] = new_tensors
    sg["operators"] = live_ops
    sg["inputs"] = [t_map[t] for t in sg["inputs"]]
    sg["outputs"] = [t_map[t] for t in sg["outputs"]]
    model["buffers"] = [model["buffers"][b] for b in keep_buffers]
    for m in model.get("metadata", []):
        m["buffer"] = b_map[m["buffer"]]
    if "metadata_buffer" in model:
        model["metadata_buffer"] = [b_map[b] for b in model["metadata_buffer"]]
    model["operator_codes"] = [model["operator_codes"][c] for c in codes]
    for sig in model.get("signature_defs", []):
        if sig.get("subgraph_index", 0) != 0:
            raise FormatError("signature defs of other subgraphs are not rewritten")
        for entry in sig.get("inputs", []) + sig.get("outputs", []):
            entry["tensor_index"] = t_map[entry.get("tensor_index", 0)]


def histogram(model):
    counts = {}
    for op in model["subgraphs"][0]["operators"]:
        name = OP_NAMES.get(opcode(model, op), str(opcode(model, op)))
        counts[name] = counts.get(name, 0) + 1
    return dict(sorted(counts.items()))



# ---------------------------------------------------------------------------------------------------------- writing
class _Builder:
    """Back-to-front flatbuffer builder (the layout of the official builders, no vtable sharing)."""

    def __init__(self, capacity=1 << 16):
        self.buf = bytearray(capacity)
        self.head = capacity
        self.minalign = 1
        self.vtable = None
        self.object_end = 0

    def offset(self):
        return len(self.buf) - self.head

    def _grow(self, need):
        while self.head < need:
            old = len(self.buf)
            bigger = bytearray(old * 2)
            bigger[old:] = self.buf
            self.buf = bigger
            self.head += old

    def prep(self, size, additional):
        self.minalign = max(self.minalign, size)
        pad = (-(self.offset() + additional)) % size
        self._grow(pad + size + additional)
        self.head -= pad  # the bytes are already zero

    def _place(self, fmt, value):
        n = struct.calcsize("<" + fmt)
        self.head -= n
        struct.pack_into("<" + fmt, self.buf, self.head, value)

    def scalar(self, fmt, value):
        self.prep(struct.calcsize("<" + fmt), 0)
        self._place(fmt, value)

    def uoffset(self, off):
        self.prep(4, 0)
        self._place("I", self.offset() - off + 4)

    def string(self, s):
        raw = s.encode("utf-8")
        self.prep(4, len(raw) + 1)
        self._place("B", 0)
        self.head -= len(raw)
        self.buf[self.head:self.head + len(raw)] = raw
        self._place("I", len(raw))
        return self.offset()

    def bytes_vector(self, raw, alignment=16):
        self.prep(4, len(raw))
        self.prep(alignment, len(raw))
        self.head -= len(raw)
        self.buf[self.head:self.head + len(raw)] = raw
        self._place("I", len(raw))
        return self.offset()

    def scalar_vector(self, fmt, values):
        size = struct.calcsize("<" + fmt)
        self.prep(4, size * len(values))
        self.prep(size, size * len(values))
        for v in reversed(values):
            self._place(fmt, v)
        self._place("I", len(values))
        return self.offset()

    def offset_vector(self, offsets):
        self.prep(4, 4 * len(offsets))
        for off in reversed(offsets):
            self.uoffset(off)
        self._place("I", len(offsets))
        return self.offset()

    def start(self, fields):
        self.vtable = [0] * fields
        self.object_end = self.offset()

    def slot(self, i):
        self.vtable[i] = self.offset()

    def end(self):
        self.scalar("i", 0)
        obj = self.offset()
        vt = self.vtable
        while vt and vt[-1] == 0:
            vt = vt[:-1]
        for field in reversed(vt):
            self.scalar("H", obj - field if field else 0)
        self.scalar("H", obj - self.object_end)
        self.scalar("H", (len(vt) + 2) * 2)
        vt_off = self.offset()
        struct.pack_into("<i", self.buf, len(self.buf) - obj, vt_off - obj)
        self.vtable = None
        return obj

    def finish(self, root, identifier=b"TFL3"):
        self.prep(self.minalign, 8)
        self.head -= 4
        self.buf[self.head:self.head + 4] = identifier
        self.uoffset(root)
        return bytes(self.buf[self.head:])


def _write_table(bld, value, fields):
    # Children first (a table refers forward to them), in field order.
    refs = {}
    for fname, kind in fields:
        if fname not in value or kind in _SCALAR:
            continue
        v = value[fname]
        if kind == "str":
            refs[fname] = bld.string(v)
        elif kind == "bytes":
            refs[fname] = bld.bytes_vector(v)
        elif kind.startswith("T:"):
            refs[fname] = _write_table(bld, v, SCHEMA[kind[2:]])
        elif kind.startswith("U:"):
            refs[fname] = _write_table(bld, v, OPTIONS[value[kind[2:]]][1])
        elif kind.startswith("[T:"):
            sub = SCHEMA[kind[3:-1]]
            refs[fname] = bld.offset_vector([_write_table(bld, t, sub) for t in v])
        elif kind.startswith("["):
            refs[fname] = bld.scalar_vector(kind[1:-1], v)
        else:
            raise FormatError(f"cannot write field kind {kind}")
    bld.start(len(fields))
    # Largest scalars first keeps the padding small; offsets are 4 bytes.
    order = sorted(range(len(fields)), key=lambda i: -_SCALAR.get(fields[i][1], 4))
    for i in order:
        fname, kind = fields[i]
        if fname not in value:
            continue
        if kind in _SCALAR:
            bld.scalar(kind, value[fname])
        else:
            bld.uoffset(refs[fname])
        bld.slot(i)
    return bld.end()


def write_model(model):
    """Encodes [model] (as read_model returns it, possibly edited) into .tflite bytes."""
    bld = _Builder()
    root = _write_table(bld, model, SCHEMA["Model"])
    return bld.finish(root)
