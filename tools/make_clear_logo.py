from PIL import Image
from pathlib import Path

src = Path(r"C:\Users\tnguy\Documents\PlayFlix\app\src\main\res\drawable\app_logo_mark.png")
out = Path(r"C:\Users\tnguy\Documents\PlayFlix\app\src\main\res\drawable\app_logo_mark_clear.png")
im = Image.open(src).convert("RGBA")
pixels = im.load()
w, h = im.size


def is_blue(r: int, g: int, b: int, a: int) -> bool:
    if a == 0:
        return True
    return b > 140 and b > r + 40 and b > g + 20 and r < 90 and g < 160


for y in range(h):
    for x in range(w):
        r, g, b, a = pixels[x, y]
        if is_blue(r, g, b, a):
            pixels[x, y] = (r, g, b, 0)

im.save(out)
print("saved", out, "corner", im.getpixel((0, 0)), "center", im.getpixel((w // 2, h // 2)))
