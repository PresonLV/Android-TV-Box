#!/usr/bin/env python3
"""生成简匣内置壁纸。全是程序绘制的渐变和色块，没有第三方图片。"""

import os
from PIL import Image, ImageDraw, ImageFilter

OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "wallpapers")
W, H = 480, 270


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def gradient(stops):
    img = Image.new("RGB", (W, H))
    px = img.load()
    for y in range(H):
        t = y / (H - 1)
        span = 1 / (len(stops) - 1)
        index = min(int(t / span), len(stops) - 2)
        local = (t - index * span) / span
        color = lerp(stops[index], stops[index + 1], local)
        for x in range(W):
            drift = (x / (W - 1) - 0.5) * 18
            px[x, y] = tuple(max(0, min(255, int(c + drift * (0.35 if i == 2 else -0.15)))) for i, c in enumerate(color))
    return img


def blobs(base, spots):
    img = base.convert("RGBA")
    overlay = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)
    for cx, cy, rx, ry, color, alpha in spots:
        draw.ellipse((cx - rx, cy - ry, cx + rx, cy + ry), fill=color + (alpha,))
    overlay = overlay.filter(ImageFilter.GaussianBlur(26))
    return Image.alpha_composite(img, overlay).convert("RGB")


SPECS = {
    "ink": ((12, 14, 19), (26, 22, 16), (16, 19, 26)),
    "dusk": ((22, 18, 32), (58, 34, 72), (16, 14, 22)),
    "ocean": ((12, 22, 28), (18, 72, 78), (10, 18, 24)),
    "forest": ((14, 22, 16), (28, 64, 42), (12, 20, 16)),
    "ember": ((28, 14, 12), (92, 48, 24), (22, 14, 12)),
    "night": ((8, 12, 28), (24, 36, 78), (10, 14, 32)),
    "sand": ((92, 68, 42), (196, 164, 112), (120, 86, 54)),
    "mist": ((214, 224, 226), (246, 244, 238), (186, 198, 204)),
    "aurora": ((8, 24, 22), (24, 72, 68), (16, 24, 58)),
    "paper": ((246, 241, 231), (228, 208, 176), (242, 234, 222)),
    "copper": ((42, 22, 16), (140, 74, 48), (28, 16, 14)),
    "plum": ((28, 14, 32), (92, 42, 84), (20, 12, 24)),
}

BLOBS = {
    "night": [(360, 54, 28, 28, (232, 226, 206), 90), (80, 180, 90, 40, (40, 70, 130), 80)],
    "aurora": [(120, 80, 110, 46, (80, 220, 170), 70), (340, 140, 120, 50, (120, 80, 200), 60)],
    "sand": [(300, 160, 140, 36, (232, 196, 140), 70)],
    "ocean": [(90, 70, 70, 24, (180, 230, 226), 50)],
    "ember": [(250, 120, 100, 40, (240, 160, 80), 55)],
    "plum": [(200, 90, 80, 36, (220, 140, 180), 50)],
    "dusk": [(320, 70, 80, 30, (180, 120, 200), 45)],
    "forest": [(140, 150, 90, 28, (120, 180, 110), 40)],
    "copper": [(180, 130, 110, 34, (210, 140, 90), 50)],
    "mist": [(240, 100, 120, 40, (255, 255, 255), 70)],
    "paper": [(160, 140, 100, 30, (255, 248, 236), 80)],
    "ink": [(300, 80, 90, 28, (80, 70, 50), 40)],
}


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, stops in SPECS.items():
        image = blobs(gradient(stops), BLOBS.get(name, []))
        path = os.path.join(OUT, name + ".webp")
        image.save(path, "WEBP", quality=52, method=6)
        print(name, os.path.getsize(path))


if __name__ == "__main__":
    main()
