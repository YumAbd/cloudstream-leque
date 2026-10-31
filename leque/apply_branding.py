#!/usr/bin/env python3
"""Apply Leque CLOUDX blue/cyan logo to adaptive and legacy launcher icons."""
from __future__ import annotations

import io
import sys
from pathlib import Path
import cairosvg
from PIL import Image

ROOT = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path("upstream").resolve()
SVG_PATH = Path(__file__).resolve().parent / "logo-cloudx.svg"

if not SVG_PATH.is_file():
    raise SystemExit(f"Missing logo asset: {SVG_PATH}")
if not (ROOT / "app/src/main/AndroidManifest.xml").is_file():
    raise SystemExit(f"Not a CloudStream checkout: {ROOT}")

logo = Image.open(io.BytesIO(cairosvg.svg2png(url=str(SVG_PATH), output_width=1600))).convert("RGBA")
bounds = logo.getbbox()
if bounds is None:
    raise SystemExit("Empty logo")
logo = logo.crop(bounds)

def make_icon(size: int, width_ratio: float, background: bool, monochrome: bool = False) -> Image.Image:
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 255) if background else (0, 0, 0, 0))
    width = round(size * width_ratio)
    height = max(1, round(width * logo.height / logo.width))
    mark = logo.resize((width, height), Image.Resampling.LANCZOS)
    if monochrome:
        alpha = mark.getchannel("A")
        mark = Image.new("RGBA", mark.size, (255, 255, 255, 0))
        mark.putalpha(alpha)
    canvas.alpha_composite(mark, ((size - width) // 2, (size - height) // 2))
    return canvas

res = ROOT / "app/src/main/res"
(res / "drawable-nodpi").mkdir(parents=True, exist_ok=True)
make_icon(432, 0.54, False).save(res / "drawable-nodpi/leque_launcher_foreground.png", optimize=True)
make_icon(432, 0.54, False, monochrome=True).save(res / "drawable-nodpi/leque_launcher_monochrome.png", optimize=True)

adaptive_xml = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background"/>
    <foreground android:drawable="@drawable/leque_launcher_foreground"/>
    <monochrome android:drawable="@drawable/leque_launcher_monochrome"/>
</adaptive-icon>
"""
sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
for variant in ("main", "debug"):
    resources = ROOT / "app/src" / variant / "res"
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        target = resources / "mipmap-anydpi-v26" / name
        if not target.is_file():
            raise SystemExit(f"Missing adaptive icon: {target}")
        target.write_text(adaptive_xml, encoding="utf-8")
    for dpi, px in sizes.items():
        folder = resources / f"mipmap-{dpi}"
        for name in ("ic_launcher.png", "ic_launcher_round.png"):
            target = folder / name
            if not target.is_file():
                raise SystemExit(f"Missing legacy icon: {target}")
            make_icon(px, 0.62, True).convert("RGB").save(target, optimize=True)
    playstore = ROOT / "app/src" / variant / "ic_launcher-playstore.png"
    if not playstore.is_file():
        raise SystemExit(f"Missing Play Store icon: {playstore}")
    make_icon(512, 0.62, True).convert("RGB").save(playstore, optimize=True)

# Android TV launcher banner (16:9, 320x180 dp): the same logo on the icon background.
# API 26+ used the adaptive mipmap-anydpi-v26/ic_banner.xml (upstream artwork): remove it so
# every Android version uses this banner.
banner = Image.new("RGBA", (640, 360), (0, 0, 0, 255))
height = round(360 * 0.5)
width = round(height * logo.width / logo.height)
if width > 640 * 0.72:
    width = round(640 * 0.72)
    height = round(width * logo.height / logo.width)
banner.alpha_composite(logo.resize((width, height), Image.Resampling.LANCZOS), ((640 - width) // 2, (360 - height) // 2))
target = res / "mipmap-xhdpi/ic_banner.png"
if not target.is_file():
    raise SystemExit(f"Missing TV banner: {target}")
banner.convert("RGB").save(target, optimize=True)
(res / "mipmap-anydpi-v26/ic_banner.xml").unlink(missing_ok=True)

print("PASS: CLOUDX blue/cyan branding applied to main + debug launcher assets and TV banner")
