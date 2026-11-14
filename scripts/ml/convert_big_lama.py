#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11,<3.13"
# dependencies = ["litert-torch==0.10.0", "torch==2.14.1", "numpy==2.5.4", "pyyaml==6.0.3"]
# ///
"""Makes the float32 LiteRT file of big-LaMa (docs/adr/0024) from the authors' checkpoint, on a host with PyTorch.

Not a build step: its output is published once as a release asset of this repository and pinned in
core/ml/models.json by checksum (the "url" of `big-lama-fp32`); the build derives the shipped int8-weight file from it
with the standard library (`convert_int8_weights.py`). Rerun it only to make a new version.

  uv run scripts/ml/convert_big_lama.py WORK_DIR OUT.tflite

What it does:
1. Downloads (cached in WORK_DIR, checksums pinned below) the LaMa source at a fixed commit (advimman/lama,
   Apache-2.0) and the big-LaMa checkpoint (Apache-2.0, trained on Places2; the Hugging Face mirror of the authors'
   release).
2. Builds the generator (`FFCResNetGenerator`, the checkpoint's own config) and loads the weights strictly.
3. Replaces each Fourier unit's `torch.fft.rfftn` / `irfftn` (no LiteRT GPU op) by the same transforms written as
   matrix products with fixed cos/sin tables (orthonormal, the 64 x 64 bottleneck of a 512 x 512 input), and checks
   the rewritten network against the original on test inputs (fails above 1e-3 max abs).
4. Exports a single-input graph: NHWC [1, 512, 512, 4] = (rgb x (1 - mask), mask), rgb in 0..1, mask 1 = fill;
   output NHWC [1, 512, 512, 3] in 0..1 (the network's sigmoid; compositing with the original is the caller's).
"""
import hashlib
import io
import os
import pickle
import sys
import types
import urllib.request
import zipfile

import numpy as np
import torch
import torch.nn as nn
import yaml

LAMA_COMMIT = "786f5936b27fb3dacd2b1ad799e4de968ea697e7"
LAMA_ZIP = f"https://codeload.github.com/advimman/lama/zip/{LAMA_COMMIT}"
CKPT_URL = ("https://huggingface.co/smartywu/big-lama/resolve/05cb2be7f8dbe6ca7c6e78f4fc827a4b2baaa4a9/"
            "big-lama.zip")
CKPT_SHA256 = "f1b358ca24093b93a106183b98a3dea6e8ed09f3b43ea7251eb2c81e7b4575f6"
SIDE = 512


def fetch(url, path, sha256=None):
    if not os.path.exists(path):
        req = urllib.request.Request(url, headers={"User-Agent": "reveal-convert/1"})
        with urllib.request.urlopen(req) as r, open(path + ".part", "wb") as f:
            while chunk := r.read(1 << 20):
                f.write(chunk)
        os.replace(path + ".part", path)
    if sha256:
        h = hashlib.sha256()
        with open(path, "rb") as f:
            while chunk := f.read(1 << 20):
                h.update(chunk)
        if h.hexdigest() != sha256:
            raise SystemExit(f"{path}: checksum {h.hexdigest()} != {sha256}")
    return path


def load_ffc(work):
    """The LaMa repository's ffc.py, with its unrelated imports stubbed (kornia, Lightning)."""
    src = fetch(LAMA_ZIP, os.path.join(work, f"lama-{LAMA_COMMIT}.zip"))
    with zipfile.ZipFile(src) as z:
        code = z.read(f"lama-{LAMA_COMMIT}/saicinpainting/training/modules/ffc.py").decode()
        se = z.read(f"lama-{LAMA_COMMIT}/saicinpainting/training/modules/squeeze_excitation.py").decode()

    def module(name, **attrs):
        m = types.ModuleType(name)
        m.__dict__.update(attrs)
        sys.modules[name] = m
        return m

    def get_activation(kind="tanh"):
        return {"tanh": nn.Tanh, "sigmoid": nn.Sigmoid}[kind]() if kind else nn.Identity()

    for pkg in ("saicinpainting", "saicinpainting.training", "saicinpainting.training.modules"):
        module(pkg)
    module("saicinpainting.training.modules.base", get_activation=get_activation, BaseDiscriminator=nn.Module)
    module("saicinpainting.training.modules.spatial_transform", LearnableSpatialTransformWrapper=None)
    module("saicinpainting.utils", get_shape=None)
    sem = module("saicinpainting.training.modules.squeeze_excitation")
    exec(compile(se, "squeeze_excitation.py", "exec"), sem.__dict__)
    ffc = module("saicinpainting.training.modules.ffc")
    exec(compile(code, "ffc.py", "exec"), ffc.__dict__)
    return ffc


class _Stub:
    def __init__(self, *a, **k):
        pass

    def __setstate__(self, state):
        pass


class _StubUnpickler(pickle.Unpickler):
    """Lightning's and omegaconf's objects in the checkpoint (hyper-parameters, callbacks) are not needed."""

    def find_class(self, module, name):
        if module.startswith(("pytorch_lightning", "lightning", "omegaconf")):
            return _Stub
        return super().find_class(module, name)


_StubPickle = types.ModuleType("_stub_pickle")
_StubPickle.Unpickler = _StubUnpickler
_StubPickle.load = pickle.load
_StubPickle.__name__ = "pickle"


def load_generator(work, ffc):
    z = fetch(CKPT_URL, os.path.join(work, "big-lama.zip"), CKPT_SHA256)
    with zipfile.ZipFile(z) as zf:
        config = yaml.safe_load(zf.read("big-lama/config.yaml"))["generator"]
        ckpt = torch.load(io.BytesIO(zf.read("big-lama/models/best.ckpt")), map_location="cpu", weights_only=False,
                          pickle_module=_StubPickle)
    kind = config.pop("kind")
    assert kind == "ffc_resnet", kind
    # resolve the config's ${...} interpolations (the two ratios that refer to other entries)
    config["downsample_conv_kwargs"]["ratio_gin"] = config["init_conv_kwargs"]["ratio_gout"]
    config["downsample_conv_kwargs"]["ratio_gout"] = config["downsample_conv_kwargs"]["ratio_gin"]
    config["resnet_conv_kwargs"]["ratio_gout"] = config["resnet_conv_kwargs"]["ratio_gin"]
    gen = ffc.FFCResNetGenerator(**config)
    state = {k[len("generator."):]: v for k, v in ckpt["state_dict"].items() if k.startswith("generator.")}
    gen.load_state_dict(state, strict=True)
    return gen.eval()


class DftFourier(nn.Module):
    """FourierUnit.forward (spectral_pos_encoding off, no SE, 2-D, ortho) with the DFTs as matrix products that LiteRT's
    GPU runs: every product has its constant on the right (FULLY_CONNECTED; the GPU refuses a BATCH_MATMUL whose
    second operand is not constant), every tensor is 4-D, and no product is added to or subtracted from another
    (litert-torch 0.10.0 fuses `matmul(a, A) - matmul(b, B)` wrongly; each product alone converts exactly).

    With C, S = cos, sin of 2 pi j k / n (orthonormal):
      along W (rfft):   x @ [C | -S]                        -> (re | im) of the half spectrum, Wr = W/2 + 1 bins
      along H (fft):    [re | im] @ [[C, -S], [S, C]]       -> (re | im), H last (after a transpose)
      1 x 1 conv + BN + ReLU over channels [re of every channel, im of every channel] (the original interleaves
      re/im per channel: its weights are permuted instead, so the values are the same)
      along H (ifft):   [re | im] @ [[C, S], [-S, C]]
      along W (irfft):  [re | im] @ [a C; -a S],  a = 1 for the DC and Nyquist bins, 2 for the others
    """

    def __init__(self, fu, h, w):
        super().__init__()
        assert not fu.use_se and not fu.spectral_pos_encoding and not fu.ffc3d and fu.fft_norm == "ortho"
        assert fu.spatial_scale_factor is None and w % 2 == 0 and fu.groups == 1
        self.h, self.wr = h, w // 2 + 1
        # interleaved (c0 re, c0 im, c1 re, ...) -> blocked (re of all, im of all), on both sides of the 1 x 1 conv
        cin = fu.conv_layer.in_channels // 2
        cout = fu.conv_layer.out_channels // 2
        pin = [2 * k for k in range(cin)] + [2 * k + 1 for k in range(cin)]
        pout = [2 * k for k in range(cout)] + [2 * k + 1 for k in range(cout)]
        self.cout = cout
        self.conv = nn.Conv2d(2 * cin, 2 * cout, 1, bias=False)
        self.conv.weight.data = fu.conv_layer.weight.data[pout][:, pin].clone()
        self.bn = nn.BatchNorm2d(2 * cout, eps=fu.bn.eps)
        for name in ("weight", "bias", "running_mean", "running_var"):
            getattr(self.bn, name).data = getattr(fu.bn, name).data[pout].clone()
        self.bn.eval()
        self.relu = nn.ReLU()
        yw, k = np.meshgrid(np.arange(w), np.arange(self.wr), indexing="ij")
        aw = 2 * np.pi * yw * k / w
        yh, kh = np.meshgrid(np.arange(h), np.arange(h), indexing="ij")
        ah = 2 * np.pi * yh * kh / h
        c_h, s_h = np.cos(ah) / np.sqrt(h), np.sin(ah) / np.sqrt(h)
        a = np.full(self.wr, 2.0)
        a[0] = a[-1] = 1.0

        def buf(name, m):
            self.register_buffer(name, torch.tensor(m, dtype=torch.float32))

        buf("fw", np.concatenate([np.cos(aw), -np.sin(aw)], axis=1) / np.sqrt(w))  # [W, 2Wr]
        buf("fh", np.block([[c_h, -s_h], [s_h, c_h]]))  # [2H, 2H]
        buf("ih", np.block([[c_h, s_h], [-s_h, c_h]]))  # [2H, 2H]
        buf("iw", np.concatenate([a[:, None] * np.cos(aw.T), -a[:, None] * np.sin(aw.T)], axis=0) / np.sqrt(w))

    def forward(self, x):
        h, wr, c2 = self.h, self.wr, self.cout
        t = torch.matmul(x, self.fw).transpose(2, 3)  # [b, c, 2wr, h]: rows re then im, H last
        t = torch.cat((t[:, :, :wr], t[:, :, wr:]), dim=3)  # [b, c, wr, 2h] = (re | im)
        y = torch.matmul(t, self.fh)  # [b, c, wr, 2h] = spectrum (re | im)
        f = torch.cat((y[..., :h], y[..., h:]), dim=1)  # [b, 2c, wr, h]: re of all channels, then im
        g = self.relu(self.bn(self.conv(f)))
        z = torch.cat((g[:, :c2], g[:, c2:]), dim=3)  # [b, c', wr, 2h] = (re | im)
        u = torch.matmul(z, self.ih).transpose(2, 3)  # [b, c', 2h, wr]: rows re then im
        u = torch.cat((u[:, :, :h], u[:, :, h:]), dim=3)  # [b, c', h, 2wr]
        return torch.matmul(u, self.iw)


class CroppedTransposeConv(nn.Module):
    """ConvTranspose2d(k 3, stride 2, padding 1, output_padding 1) = the unpadded transposed convolution cropped to
    [1, 2H + 1): the same values, without the output padding LiteRT's converter cannot lower."""

    def __init__(self, t):
        super().__init__()
        assert t.kernel_size == (3, 3) and t.stride == (2, 2) and t.padding == (1, 1) and t.output_padding == (1, 1)
        self.t = nn.ConvTranspose2d(t.in_channels, t.out_channels, 3, stride=2, padding=0, bias=t.bias is not None)
        self.t.load_state_dict(t.state_dict())

    def forward(self, x):
        h, w = x.shape[-2:]
        return self.t(x)[:, :, 1:2 * h + 1, 1:2 * w + 1]


def rewrite(gen, ffc, side):
    bottleneck = side // 8
    n = 0
    for mod in gen.modules():
        if isinstance(mod, ffc.SpectralTransform):
            assert not mod.enable_lfu and isinstance(mod.downsample, nn.Identity)
            mod.fu = DftFourier(mod.fu, bottleneck, bottleneck)
            n += 1
    seq = gen.model
    for i, mod in enumerate(seq):
        if isinstance(mod, nn.ConvTranspose2d):
            seq[i] = CroppedTransposeConv(mod)
    return n


class Nhwc(nn.Module):
    def __init__(self, gen):
        super().__init__()
        self.gen = gen

    def forward(self, x):  # [1, H, W, 4] = (rgb x (1 - mask), mask)
        return self.gen(x.permute(0, 3, 1, 2)).permute(0, 2, 3, 1)


def test_inputs(n=3, seed=0):
    g = torch.Generator().manual_seed(seed)
    out = []
    for i in range(n):
        yy, xx = torch.meshgrid(torch.linspace(0, 1, SIDE), torch.linspace(0, 1, SIDE), indexing="ij")
        rgb = torch.stack([xx, yy, (xx + yy) / 2]) * 0.6 + torch.rand(3, SIDE, SIDE, generator=g) * 0.4
        mask = torch.zeros(1, SIDE, SIDE)
        r = 60 + 50 * i
        mask[:, SIDE // 2 - r:SIDE // 2 + r, SIDE // 3 - r // 2:SIDE // 3 + r // 2] = 1
        out.append(torch.cat([rgb * (1 - mask), mask])[None])
    return out


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    work, out = sys.argv[1], sys.argv[2]
    os.makedirs(work, exist_ok=True)
    torch.manual_seed(0)
    ffc = load_ffc(work)
    reference = load_generator(work, ffc)
    gen = load_generator(work, ffc)
    print("fourier units rewritten:", rewrite(gen, ffc, SIDE))
    with torch.no_grad():
        for x in test_inputs():
            d = (gen(x) - reference(x)).abs()
            print(f"rewrite vs torch.fft: max {d.max():.2e} mean {d.mean():.2e}")
            if d.max() > 1e-3:
                raise SystemExit("the DFT rewrite does not match the original")
    import litert_torch

    sample = (test_inputs(1)[0].permute(0, 2, 3, 1).contiguous(),)
    edge = litert_torch.convert(Nhwc(gen).eval(), sample)
    edge.export(out)
    with torch.no_grad():
        for x in test_inputs(4, seed=1):
            x = x.permute(0, 2, 3, 1).contiguous()
            d = np.abs(edge(x) - Nhwc(reference)(x).numpy())
            print(f"tflite (host CPU) vs original: max {d.max():.2e} mean {d.mean():.2e}")
            if d.max() > 1e-3:
                raise SystemExit("the converted model does not match the original")
    h = hashlib.sha256(open(out, "rb").read()).hexdigest()
    print(f"wrote {out} ({os.path.getsize(out)} bytes, sha256 {h})")


if __name__ == "__main__":
    main()
