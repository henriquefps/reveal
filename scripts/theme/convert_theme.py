#!/usr/bin/env python3
"""Convert the hfps `theme.css` (OKLCH tokens) into Reveal's RevealTokens.kt, and check WCAG contrast.

    python3 scripts/theme/convert_theme.py ~/.agents/skills/hfps-app-ui/assets/theme.css          # regenerate
    python3 scripts/theme/convert_theme.py ~/.agents/skills/hfps-app-ui/assets/theme.css --check  # CI: no diff

Reads the `:root { }` block (light) and the `.dark { }` block (dark). Emits sRGB `Color`s for every token, plus
Display P3 variants (`<name>P3`) for `primary`, `ring` and any token whose OKLCH colour is outside sRGB.
Exits non-zero if a contrast pair below regresses (WCAG AA, 4.5:1 for text). No third-party dependencies.
"""
import math
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "core/designsystem/src/main/kotlin/dev/hfps/reveal/designsystem/theme/RevealTokens.kt"

TOKENS = [
    "background", "foreground", "card", "card-foreground", "popover", "popover-foreground", "primary",
    "primary-foreground", "secondary", "secondary-foreground", "muted", "muted-foreground", "accent",
    "accent-foreground", "destructive", "warning", "border", "input", "ring",
]
P3_ALWAYS = {"primary", "ring"}

# (foreground token, background token, minimum ratio). The pairs the hfps-app-ui skill lists ("Contrast").
PAIRS = [
    ("foreground", "background", 4.5), ("card-foreground", "card", 4.5),
    ("muted-foreground", "background", 4.5), ("muted-foreground", "card", 4.5), ("muted-foreground", "muted", 4.5),
    ("destructive", "background", 4.5), ("destructive", "card", 4.5), ("destructive", "muted", 4.5),
    ("primary", "background", 4.5), ("primary", "card", 4.5), ("primary-foreground", "primary", 4.5),
    ("warning", "background", 3.0), ("warning", "card", 3.0),
]


def parse_block(css, selector):
    m = re.search(re.escape(selector) + r"\s*\{(.*?)\n\}", css, re.S)
    if not m:
        sys.exit(f"theme.css: block {selector} not found")
    values = {}
    for name, val in re.findall(r"--([a-z0-9-]+):\s*([^;]+);", m.group(1)):
        if name in TOKENS:
            values[name] = val.strip()
    missing = [t for t in TOKENS if t not in values]
    if missing:
        sys.exit(f"theme.css: {selector} lacks {missing}")
    return values


def parse_oklch(s):
    m = re.fullmatch(r"oklch\(\s*([\d.]+)\s+([\d.]+)\s+([\d.]+)\s*(?:/\s*([\d.]+)(%?))?\s*\)", s)
    if not m:
        sys.exit(f"unsupported colour: {s}")
    alpha = 1.0
    if m.group(4):
        alpha = float(m.group(4)) / (100 if m.group(5) else 1)
    return float(m.group(1)), float(m.group(2)), float(m.group(3)), alpha


def oklch_to_linear_srgb(L, C, h):
    a, b = C * math.cos(math.radians(h)), C * math.sin(math.radians(h))
    l_, m_, s_ = L + 0.3963377774 * a + 0.2158037573 * b, L - 0.1055613458 * a - 0.0638541728 * b, L - 0.0894841775 * a - 1.2914855480 * b
    l, m, s = l_ ** 3, m_ ** 3, s_ ** 3
    return (4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
            -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
            -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s)


def linear_srgb_to_linear_p3(rgb):
    r, g, b = rgb
    x = 0.4124564 * r + 0.3575761 * g + 0.1804375 * b
    y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
    z = 0.0193339 * r + 0.1191920 * g + 0.9503041 * b
    return (2.4934969 * x - 0.9313836 * y - 0.4027108 * z,
            -0.8294890 * x + 1.7626641 * y + 0.0236247 * z,
            0.0358458 * x - 0.0761724 * y + 0.9568845 * z)


def encode(c):
    c = min(1.0, max(0.0, c))
    return 12.92 * c if c <= 0.0031308 else 1.055 * c ** (1 / 2.4) - 0.055


def decode(c):
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


EPS = 1e-4


def in_gamut(rgb):
    return all(-EPS <= c <= 1 + EPS for c in rgb)


def convert(value):
    L, C, h, alpha = parse_oklch(value)
    lin = oklch_to_linear_srgb(L, C, h)
    srgb = tuple(encode(c) for c in lin)
    p3_lin = linear_srgb_to_linear_p3(lin)
    p3 = tuple(encode(c) for c in p3_lin)
    return {"srgb": srgb, "p3": p3, "alpha": alpha, "out_of_srgb": not in_gamut(lin)}


def hex_argb(c):
    r, g, b = (round(x * 255) for x in c["srgb"])
    a = round(c["alpha"] * 255)
    return f"0x{a:02X}{r:02X}{g:02X}{b:02X}"


def luminance(rgb):
    r, g, b = (decode(x) for x in rgb)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def composite(fg, bg_rgb):
    a = fg["alpha"]
    return tuple(a * f + (1 - a) * b for f, b in zip(fg["srgb"], bg_rgb))


def quantised(c):
    return tuple(round(x * 255) / 255 for x in c["srgb"])


def contrast(fg, bg):
    bg_rgb = quantised(bg)
    fg_rgb = composite({"srgb": quantised(fg), "alpha": round(fg["alpha"] * 255) / 255}, bg_rgb)
    l1, l2 = luminance(fg_rgb), luminance(bg_rgb)
    hi, lo = max(l1, l2), min(l1, l2)
    return (hi + 0.05) / (lo + 0.05)


def camel(name):
    parts = name.split("-")
    return parts[0] + "".join(p.capitalize() for p in parts[1:])


def emit(mode, tokens):
    lines = [f"    /** {mode} tokens, converted from the `{'.dark' if mode == 'Dark' else ':root'}` block. */",
             f"    object {mode} {{"]
    for name in TOKENS:
        c = tokens[name]
        lines.append(f"        val {camel(name)} = Color({hex_argb(c)})")
        if name in P3_ALWAYS or c["out_of_srgb"]:
            r, g, b = (f"{x:.4f}f" for x in c["p3"])
            lines.append(f"        val {camel(name)}P3 = Color({r}, {g}, {b}, {c['alpha']:.2f}f, ColorSpaces.DisplayP3)")
    lines.append("    }")
    return lines


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if len(args) != 1:
        sys.exit(__doc__)
    css = Path(args[0]).expanduser().read_text()
    modes = {"Light": parse_block(css, ":root"), "Dark": parse_block(css, "\n.dark")}
    converted = {m: {k: convert(v) for k, v in toks.items()} for m, toks in modes.items()}

    out = [
        "// GENERATED by scripts/theme/convert_theme.py from the hfps-app-ui theme.css. Do not edit.",
        "// Regenerate: python3 scripts/theme/convert_theme.py ~/.agents/skills/hfps-app-ui/assets/theme.css",
        '@file:Suppress("MagicNumber")',
        "",
        "package dev.hfps.reveal.designsystem.theme",
        "",
        "import androidx.compose.ui.graphics.Color",
        "import androidx.compose.ui.graphics.colorspace.ColorSpaces",
        "",
        "/** The hfps OKLCH tokens as sRGB colours, with Display P3 (`...P3`) variants where they matter. */",
        "object RevealTokens {",
    ]
    for mode in ("Light", "Dark"):
        out += emit(mode, converted[mode])
        if mode == "Light":
            out.append("")
    out.append("}")
    text = "\n".join(out) + "\n"

    failed = False
    print("WCAG contrast (sRGB, after 8-bit quantisation):")
    for mode, toks in converted.items():
        for fg, bg, minimum in PAIRS:
            ratio = contrast(toks[fg], toks[bg])
            ok = ratio >= minimum
            failed |= not ok
            print(f"  {mode:5} {fg:18} on {bg:11} {ratio:5.2f}:1 (min {minimum}) {'ok' if ok else 'FAIL'}")

    if "--check" in sys.argv:
        if not OUT.exists() or OUT.read_text() != text:
            print(f"{OUT} is out of date")
            failed = True
    else:
        OUT.parent.mkdir(parents=True, exist_ok=True)
        OUT.write_text(text)
        print(f"wrote {OUT.relative_to(ROOT)}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
