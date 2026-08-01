"""Build a splash-safe transparent mark from app_logo_mark.png.

The source is a blue-tile export with white anti-alias fringe. Punching only blue
leaves a light halo on dark canvases — also key out white/gray fringe and normalize
the black body + yellow accents.
"""
from __future__ import annotations

from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/res/drawable/app_logo_mark.png"
OUT = ROOT / "app/src/main/res/drawable/app_logo_mark_clear.png"


def is_blue(r: int, g: int, b: int) -> bool:
    # Full blue tile + leftover blue AA fringe after a soft export.
    if b > 140 and b > r + 40 and b > g + 20 and r < 90 and g < 160:
        return True
    return b > 85 and b > r + 40 and b > g + 25 and r < 45 and g < 80


def is_yellow(r: int, g: int, b: int) -> bool:
    return r > 170 and g > 140 and b < 130 and r + g > b * 2.2


def is_near_white(r: int, g: int, b: int) -> bool:
    return min(r, g, b) > 175


def is_grayish(r: int, g: int, b: int) -> bool:
    return abs(r - g) < 28 and abs(g - b) < 28 and abs(r - b) < 28


def main() -> None:
    im = Image.open(SRC).convert("RGBA")
    pixels = im.load()
    w, h = im.size

    for y in range(h):
        for x in range(w):
            r, g, b, a = pixels[x, y]
            if a == 0 or is_blue(r, g, b) or is_near_white(r, g, b):
                pixels[x, y] = (0, 0, 0, 0)
                continue
            if is_yellow(r, g, b):
                pixels[x, y] = (255, 204, 0, 255)
                continue
            lum = (r + g + b) / 3.0
            if lum < 55:
                pixels[x, y] = (0, 0, 0, 255)
                continue
            if is_grayish(r, g, b):
                # White-bg AA on the black silhouette → soft black edge, not a white halo.
                alpha = int(max(0, min(255, 255 * (1.0 - lum / 255.0))))
                pixels[x, y] = (0, 0, 0, alpha)
                continue
            pixels[x, y] = (r, g, b, a)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    im.save(OUT)
    print("saved", OUT, "size", im.size, "corner", im.getpixel((0, 0)))


if __name__ == "__main__":
    main()
