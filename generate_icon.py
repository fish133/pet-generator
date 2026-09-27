#!/usr/bin/env python3
"""
生成应用图标（蓝色渐变 + 白色爪印）
运行: python3 generate_icon.py
输出: app/src/main/res/mipmap-{密度}/ic_launcher.png 和 ic_launcher_round.png
"""
from PIL import Image, ImageDraw
import os

def make_icon(size):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    for y in range(size):
        r = int(100 + (25 - 100) * y / size)
        g = int(181 + (118 - 181) * y / size)
        b = int(246 + (210 - 246) * y / size)
        draw.line([(0, y), (size, y)], fill=(r, g, b, 255))
    mask = Image.new("L", (size, size), 0)
    md = ImageDraw.Draw(mask)
    md.rounded_rectangle([0, 0, size - 1, size - 1], radius=int(size * 0.22), fill=255)
    img.putalpha(mask)
    paint = ImageDraw.Draw(img)
    white = (255, 255, 255, 255)
    cx, cy = size // 2, int(size * 0.55)
    paint.ellipse([cx - size * 0.12, cy - size * 0.09, cx + size * 0.12, cy + size * 0.09], fill=white)
    toe_r = size * 0.04
    toe_y = size * 0.38
    for dx in [-size * 0.12, -size * 0.04, size * 0.04, size * 0.12]:
        paint.ellipse([cx + dx - toe_r, toe_y - toe_r, cx + dx + toe_r, toe_y + toe_r], fill=white)
    return img

if __name__ == "__main__":
    densities = {"mipmap-mdpi": 48, "mipmap-hdpi": 72, "mipmap-xhdpi": 96, "mipmap-xxhdpi": 144, "mipmap-xxxhdpi": 192}
    base = "app/src/main/res"
    for folder, size in densities.items():
        path = os.path.join(base, folder)
        os.makedirs(path, exist_ok=True)
        icon = make_icon(size)
        icon.save(os.path.join(path, "ic_launcher.png"))
        icon.save(os.path.join(path, "ic_launcher_round.png"))
        print(f"  {folder}: {size}x{size} 完成")
    print("图标生成完成！")