#!/usr/bin/env python3
"""Fetches Reveal's on-device mask models (docs/adr/0021) into the build, checked against pinned SHA-256.

The models are never committed and never downloaded at runtime: the build fetches the pinned files listed in
`core/ml/models.json` (URL, SHA-256, licence) into a cache (`$REVEAL_ML_CACHE`, default `~/.cache/reveal-ml`, keyed
by checksum) and copies the ones the APK carries into `core/ml/build/generated/ml-assets/<variant>/ml/<asset>`, which
Gradle adds to `:core:ml`'s assets. A file whose checksum differs is rejected and deleted (a moved or changed upstream
file fails the build; it never ships silently).

Which files a build carries: `"bundled": true` = every build (release and debug); `"lab": true` = debug builds only
(the mask lab's comparisons, docs/adr/0021 "M4/C"). A `derive` entry has no URL: it is made on the build machine from
another pinned entry, deterministically, and its output checksum is pinned like a download's:
- `{"from": <id>@<version>, "convert": <script in scripts/ml>}` runs the script's `convert(bytes) -> bytes`
  (convert_deeplab_sky.py: the GPU-friendly sky model; convert_fp16_weights.py: float16 weights);
- `{"from": <id>@<version>, "extract": <member>}` takes one member of a zip (a MediaPipe `.task` bundle).
Entries are keyed by `<id>@<version>` (what a recipe stores): a new file of a model is a new version.

  scripts/ml/fetch_models.py --variant release  # cache + copy what a release build carries
  scripts/ml/fetch_models.py --variant debug    # ... a debug build (release + the lab's extra models)
  scripts/ml/fetch_models.py --all --out DIR    # every candidate, bundled or not (benchmarks)
  scripts/ml/fetch_models.py --check            # re-download every pinned file and regenerate every derived one,
                                                #   bypassing the cache, and verify them
  scripts/ml/fetch_models.py --paths            # print id@version -> cached path (host checks: verify_*.py)

Standard library only.
"""
import argparse
import hashlib
import importlib.util
import io
import json
import os
import shutil
import sys
import tempfile
import urllib.request
import zipfile

sys.dont_write_bytecode = True  # the derive scripts are imported: no __pycache__ in the repo

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MANIFEST = os.path.join(ROOT, "core/ml/models.json")
DEFAULT_OUT = os.path.join(ROOT, "core/ml/build/generated/ml-assets")
CACHE = os.environ.get("REVEAL_ML_CACHE") or os.path.join(os.path.expanduser("~"), ".cache", "reveal-ml")
USER_AGENT = "reveal-build (model fetch)"


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def download(url, expected, dest):
    """Downloads [url] to [dest] through a temporary file and verifies its SHA-256."""
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(dest), suffix=".part")
    os.close(fd)
    try:
        req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(req, timeout=120) as r, open(tmp, "wb") as f:
            shutil.copyfileobj(r, f, 1 << 20)
        got = sha256(tmp)
        if got != expected:
            raise SystemExit(f"checksum mismatch for {url}\n  expected {expected}\n  got      {got}")
        os.replace(tmp, dest)
    finally:
        if os.path.exists(tmp):
            os.remove(tmp)


def derive(model, models, refresh):
    """The bytes of a derived entry, made from its (cached, verified) source entry."""
    rule = model["derive"]
    source = cached(models[rule["from"]], models, refresh)
    with open(source, "rb") as f:
        data = f.read()
    if "convert" in rule:
        script = os.path.join(os.path.dirname(os.path.abspath(__file__)), rule["convert"])
        spec = importlib.util.spec_from_file_location("reveal_convert", script)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module.convert(data)
    if "extract" in rule:
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            return z.read(rule["extract"])
    raise SystemExit(f"{model['id']}: unknown derive rule {rule}")


def cached(model, models, refresh=False):
    path = os.path.join(CACHE, model["sha256"])
    if refresh or not os.path.exists(path) or sha256(path) != model["sha256"]:
        if "derive" in model:
            print(f"deriving {model['id']}@{model['version']} from {model['derive']['from']}", file=sys.stderr)
            out = derive(model, models, refresh)
            got = hashlib.sha256(out).hexdigest()
            if got != model["sha256"]:
                raise SystemExit(f"derived {model['id']} differs\n  expected {model['sha256']}\n  got      {got}")
            os.makedirs(CACHE, exist_ok=True)
            fd, tmp = tempfile.mkstemp(dir=CACHE, suffix=".part")
            with os.fdopen(fd, "wb") as f:
                f.write(out)
            os.replace(tmp, path)
        else:
            print(f"fetching {model['id']} ({model['url']})", file=sys.stderr)
            download(model["url"], model["sha256"], path)
    return path


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default=None, help="assets root (files go to <out>/ml/<asset>)")
    ap.add_argument("--variant", choices=["release", "debug"], default="debug", help="which build's set")
    ap.add_argument("--all", action="store_true", help="every candidate, not only the bundled ones")
    ap.add_argument("--check", action="store_true", help="re-download and verify every pinned file")
    ap.add_argument("--paths", action="store_true", help="print id and cached path of every entry")
    args = ap.parse_args()

    with open(MANIFEST, encoding="utf-8") as f:
        entries = json.load(f)["models"]
    models = {f"{m['id']}@{m['version']}": m for m in entries}
    if len(models) != len(entries) or len({m["asset"] for m in entries}) != len(entries):
        raise SystemExit("duplicate model id@version or asset in models.json")

    if args.check:
        for m in entries:
            cached(m, models, refresh=True)
        print(f"ok: {len(entries)} pinned files verified")
        return
    if args.paths:
        for m in entries:
            print(f"{m['id']}@{m['version']}", cached(m, models))
        return

    out = os.path.join(args.out or os.path.join(DEFAULT_OUT, args.variant), "ml")
    os.makedirs(out, exist_ok=True)
    wanted = {m["asset"] for m in entries
              if args.all or m.get("bundled") or (args.variant == "debug" and m.get("lab"))}
    for m in entries:
        if m["asset"] not in wanted:
            continue
        dest = os.path.join(out, m["asset"])
        if os.path.exists(dest) and os.path.getsize(dest) == m["bytes"] and sha256(dest) == m["sha256"]:
            continue
        shutil.copyfile(cached(m, models), dest)
    # Stale files (a model dropped from the manifest) never linger in the APK.
    for name in os.listdir(out):
        if name not in wanted:
            os.remove(os.path.join(out, name))


if __name__ == "__main__":
    main()
