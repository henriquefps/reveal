#!/usr/bin/env python3
"""Converts MediaPipe's BlazeFace full-range face detector (Apache-2.0) into Reveal's network asset (docs/adr/0010, "Faces").

Fetches the pinned `face_detection_full_range.tflite` (checked against SHA256), reads its TensorFlow Lite flatbuffer
with the small reader below (standard library only), folds the float16 DEQUANTIZE ops into float16 constants and
writes the graph as a compact little-endian binary that `dev.hfps.reveal.pipeline.faces.NetworkGraph` executes in
pure Kotlin. The asset is the model's weights re-encoded, so it stays under Apache-2.0 (docs/licences.md);
regenerate, never hand-edit.

  scripts/faces/convert_blazeface.py            # writes the asset
  scripts/faces/convert_blazeface.py --check    # fails when the committed asset differs from a fresh conversion

Format (all little-endian int32 unless noted):
  "RVNN" version=1
  tensors: count, then per tensor: rank, dims[rank], kind (0 activation, 1 float16 data, 2 int32 data),
           element count, data (float16 as uint16, int32) for constants
  ops:     count, then per op: code, n inputs, inputs[], n outputs, outputs[], n options, options[]
  graph:   input tensor, n outputs, outputs[]
Op codes and options:
  1 CONV_2D (padding 0 SAME / 1 VALID, stride w, stride h)      filter OHWI, bias
  2 DEPTHWISE_CONV_2D (padding, stride w, stride h)              filter 1HWC, bias (depth multiplier 1)
  3 MAX_POOL_2D (padding, stride w, stride h, filter w, filter h)
  4 ADD   5 RELU   6 PAD (paddings tensor, int32 [rank][2])
  7 RESIZE_BILINEAR (align corners, half-pixel centres; size tensor)   8 RESHAPE
"""
import gzip
import hashlib
import os
import struct
import sys
import urllib.request

URL = "https://storage.googleapis.com/mediapipe-assets/face_detection_full_range.tflite"
SHA256 = "3698b18f063835bc609069ef052228fbe86d9c9a6dc8dcb7c7c2d69aed2b181b"
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "core/pipeline/src/main/resources/faces/blazeface-full-range.rvnn.gz")

# TensorFlow Lite builtin operator codes and tensor types (schema.fbs).
ADD, CONV_2D, DEPTHWISE_CONV_2D, DEQUANTIZE, MAX_POOL_2D, RELU, RESHAPE, RESIZE_BILINEAR, PAD = 0, 3, 4, 6, 17, 19, 22, 23, 34
FLOAT32, FLOAT16, INT32 = 0, 1, 2
OWN = {CONV_2D: 1, DEPTHWISE_CONV_2D: 2, MAX_POOL_2D: 3, ADD: 4, RELU: 5, PAD: 6, RESIZE_BILINEAR: 7, RESHAPE: 8}


class Table:
    """A flatbuffer table: fields by index, scalars with defaults, offsets followed."""

    def __init__(self, buf, pos):
        self.buf, self.pos = buf, pos
        self.vt = pos - struct.unpack_from("<i", buf, pos)[0]
        self.vt_len = struct.unpack_from("<H", buf, self.vt)[0]

    def _field(self, i):
        o = 4 + 2 * i
        if o >= self.vt_len:
            return 0
        return struct.unpack_from("<H", self.buf, self.vt + o)[0]

    def scalar(self, i, fmt, default=0):
        o = self._field(i)
        return struct.unpack_from("<" + fmt, self.buf, self.pos + o)[0] if o else default

    def _target(self, i):
        o = self._field(i)
        if not o:
            return None
        p = self.pos + o
        return p + struct.unpack_from("<I", self.buf, p)[0]

    def table(self, i):
        p = self._target(i)
        return Table(self.buf, p) if p is not None else None

    def vector(self, i):
        p = self._target(i)
        if p is None:
            return 0, 0
        return p + 4, struct.unpack_from("<I", self.buf, p)[0]

    def tables(self, i):
        start, n = self.vector(i)
        return [Table(self.buf, start + 4 * k + struct.unpack_from("<I", self.buf, start + 4 * k)[0]) for k in range(n)]

    def ints(self, i, fmt="i"):
        start, n = self.vector(i)
        size = struct.calcsize(fmt)
        return [struct.unpack_from("<" + fmt, self.buf, start + size * k)[0] for k in range(n)]

    def bytes(self, i):
        start, n = self.vector(i)
        return self.buf[start:start + n]


def convert(model_bytes):
    model = Table(model_bytes, struct.unpack_from("<I", model_bytes, 0)[0])
    codes = [max(c.scalar(0, "b"), c.scalar(3, "i")) for c in model.tables(1)]
    buffers = [b.bytes(0) for b in model.tables(4)]
    graph = model.tables(2)[0]
    tensors = graph.tables(0)
    ops = graph.tables(3)

    alias = {}  # DEQUANTIZE output -> its float16 constant input
    kept = []
    for op in ops:
        code = codes[op.scalar(0, "I")]
        ins, outs = op.ints(1), op.ints(2)
        if code == DEQUANTIZE:
            alias[outs[0]] = ins[0]
            continue
        if code not in OWN:
            raise SystemExit(f"unsupported operator {code}")
        opts = op.table(4)
        if code == CONV_2D:
            assert opts.scalar(3, "b") == 0 and opts.scalar(4, "i", 1) == 1, "fused activation / dilation"
            options = [opts.scalar(0, "b"), opts.scalar(1, "i"), opts.scalar(2, "i")]
        elif code == DEPTHWISE_CONV_2D:
            assert opts.scalar(3, "i") == 1 and opts.scalar(4, "b") == 0 and opts.scalar(5, "i", 1) == 1
            options = [opts.scalar(0, "b"), opts.scalar(1, "i"), opts.scalar(2, "i")]
        elif code == MAX_POOL_2D:
            assert opts.scalar(5, "b") == 0
            options = [opts.scalar(i, "b" if i == 0 else "i") for i in range(5)]
        elif code == ADD:
            assert opts.scalar(0, "b") == 0
            options = []
        elif code == RESIZE_BILINEAR:
            options = [int(opts.scalar(2, "?")), int(opts.scalar(3, "?"))]
        else:
            options = []
        kept.append((OWN[code], [alias.get(t, t) for t in ins], outs, options))

    used = sorted({t for _, ins, outs, _ in kept for t in ins + outs})
    index = {t: k for k, t in enumerate(used)}
    out = bytearray(b"RVNN")
    out += struct.pack("<ii", 1, len(used))
    for t in used:
        tensor = tensors[t]
        shape = tensor.ints(0)
        data = buffers[tensor.scalar(2, "I")]
        ttype = tensor.scalar(1, "b")
        out += struct.pack("<i", len(shape)) + struct.pack(f"<{len(shape)}i", *shape)
        if not data:
            out += struct.pack("<ii", 0, 0)
        elif ttype == FLOAT16:
            out += struct.pack("<ii", 1, len(data) // 2) + data
        elif ttype == INT32:
            out += struct.pack("<ii", 2, len(data) // 4) + data
        elif ttype == FLOAT32:  # stored as float16 (none in this model; kept for completeness)
            values = struct.unpack(f"<{len(data) // 4}f", data)
            out += struct.pack("<ii", 1, len(values)) + struct.pack(f"<{len(values)}e", *values)
        else:
            raise SystemExit(f"unsupported tensor type {ttype}")
    out += struct.pack("<i", len(kept))
    for code, ins, outs, options in kept:
        out += struct.pack("<ii", code, len(ins)) + struct.pack(f"<{len(ins)}i", *[index[t] for t in ins])
        out += struct.pack("<i", len(outs)) + struct.pack(f"<{len(outs)}i", *[index[t] for t in outs])
        out += struct.pack("<i", len(options)) + struct.pack(f"<{len(options)}i", *options)
    graph_in = graph.ints(1)
    graph_out = graph.ints(2)
    out += struct.pack("<ii", index[graph_in[0]], len(graph_out))
    out += struct.pack(f"<{len(graph_out)}i", *[index[t] for t in graph_out])
    # mtime 0: the gzip stream is reproducible, so --check can compare bytes.
    return gzip.compress(bytes(out), compresslevel=9, mtime=0)


def main():
    with urllib.request.urlopen(URL) as r:
        model = r.read()
    digest = hashlib.sha256(model).hexdigest()
    if digest != SHA256:
        raise SystemExit(f"unexpected model checksum {digest}")
    data = convert(model)
    if "--check" in sys.argv:
        with open(OUT, "rb") as f:
            if f.read() != data:
                raise SystemExit("faces asset is stale: run scripts/faces/convert_blazeface.py")
        print("faces asset up to date")
        return
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "wb") as f:
        f.write(data)
    print(f"wrote {OUT} ({len(data)} bytes)")


if __name__ == "__main__":
    main()
