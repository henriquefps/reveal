#!/usr/bin/env python3
"""Converts the lensfun database (CC BY-SA 3.0) into Reveal's compact lens-profile asset (docs/adr/0015).

Fetches the pinned lensfun commit, keeps what the lens corrections need (cameras: maker, model, mount, crop factor;
lenses with at least one calibration: names, mounts, calibration crop factor and aspect ratio, distortion, TCA and
vignetting at the farthest focus distance) and writes it as gzipped JSON to
core/pipeline/src/main/resources/lensfun/lensfun.json.gz. The output is an adaptation of the database and stays under
CC BY-SA 3.0 (see docs/licences.md); regenerate, never hand-edit.

  scripts/lensfun/convert_lensfun.py            # writes the asset
  scripts/lensfun/convert_lensfun.py --check    # fails when the committed asset differs from a fresh conversion
"""
import gzip
import io
import json
import os
import sys
import tarfile
import urllib.request
import xml.etree.ElementTree as ET

COMMIT = "52cf1a7b0e82e07e0ecaa3391ee5106a6dd1ee54"
URL = f"https://github.com/lensfun/lensfun/archive/{COMMIT}.tar.gz"
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "core/pipeline/src/main/resources/lensfun/lensfun.json.gz")


def text(e, tag):
    for c in e.findall(tag):
        if c.get("lang") is None and c.text:
            return c.text.strip()
    return None


def names(e, tag):
    return sorted({c.text.strip() for c in e.findall(tag) if c.text})


def num(v):
    f = float(v)
    return int(f) if f == int(f) and abs(f) < 1e6 else round(f, 7)


def aspect(e):
    a = e.findtext("aspect-ratio")
    if not a:
        return 1.5
    if ":" in a:
        w, h = a.split(":")
        return round(float(w) / float(h), 6)
    return float(a)


def convert(files):
    cameras, lenses = [], []
    for name in sorted(files):
        root = ET.fromstring(files[name])
        for c in root.iter("camera"):
            maker, model, mount = text(c, "maker"), text(c, "model"), c.findtext("mount")
            crop = c.findtext("cropfactor")
            if maker and model and crop:
                cameras.append([maker, model, mount or "", num(crop)])
        for l in root.iter("lens"):
            cal = l.find("calibration")
            if cal is None or len(cal) == 0:
                continue
            crop = l.findtext("cropfactor")
            if not crop:
                continue
            dist, tca, vig = [], [], {}
            for d in cal.findall("distortion"):
                m = d.get("model")
                if m == "ptlens":
                    dist.append([num(d.get("focal")), "ptlens", num(d.get("a", 0)), num(d.get("b", 0)), num(d.get("c", 0))])
                elif m == "poly3":
                    dist.append([num(d.get("focal")), "poly3", num(d.get("k1", 0))])
                elif m == "poly5":
                    dist.append([num(d.get("focal")), "poly5", num(d.get("k1", 0)), num(d.get("k2", 0))])
            for t in cal.findall("tca"):
                m = t.get("model")
                if m == "linear":
                    tca.append([num(t.get("focal")), "linear", num(t.get("kr", 1)), num(t.get("kb", 1))])
                elif m == "poly3":
                    tca.append([num(t.get("focal")), "poly3"] + [num(t.get(k, 1 if k in ("vr", "vb") else 0))
                                                              for k in ("vr", "vb", "cr", "cb", "br", "bb")])
            for v in cal.findall("vignetting"):
                if v.get("model") != "pa":
                    continue
                key = (num(v.get("focal")), num(v.get("aperture")))
                distance = float(v.get("distance", 1000))
                entry = (distance, [key[0], key[1], num(v.get("k1", 0)), num(v.get("k2", 0)), num(v.get("k3", 0))])
                if key not in vig or distance > vig[key][0]:
                    vig[key] = entry
            if not dist and not tca and not vig:
                continue
            lens = {
                "maker": text(l, "maker") or "",
                "models": names(l, "model"),
                "mounts": names(l, "mount"),
                "crop": num(crop),
            }
            a = aspect(l)
            if a != 1.5:
                lens["aspect"] = a
            if dist:
                lens["d"] = sorted(dist, key=lambda x: x[0])
            if tca:
                lens["t"] = sorted(tca, key=lambda x: x[0])
            if vig:
                lens["v"] = sorted((e for _, e in vig.values()), key=lambda x: (x[0], x[1]))
            lenses.append(lens)
    return {"source": f"lensfun {COMMIT}", "licence": "CC BY-SA 3.0", "cameras": cameras, "lenses": lenses}


def fetch():
    with urllib.request.urlopen(URL, timeout=120) as r:
        data = r.read()
    files = {}
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as tar:
        for m in tar.getmembers():
            if "/data/db/" in m.name and m.name.endswith(".xml"):
                files[os.path.basename(m.name)] = tar.extractfile(m).read()
    return files


def main():
    db = convert(fetch())
    payload = json.dumps(db, separators=(",", ":"), sort_keys=True).encode()
    blob = gzip.compress(payload, mtime=0)
    if "--check" in sys.argv:
        with open(OUT, "rb") as f:
            if gzip.decompress(f.read()) != payload:
                sys.exit("lensfun asset is stale: run scripts/lensfun/convert_lensfun.py")
        print("lensfun asset up to date")
        return
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "wb") as f:
        f.write(blob)
    print(f"{len(db['cameras'])} cameras, {len(db['lenses'])} lenses, {len(payload)} bytes JSON, {len(blob)} gzipped")


if __name__ == "__main__":
    main()
