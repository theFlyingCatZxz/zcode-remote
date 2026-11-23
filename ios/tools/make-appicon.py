#!/usr/bin/env python3
"""ZCode Remote iOS 应用图标渲染器（Pillow + numpy，零手工素材）。

设计与 mac/tools/make-icon.swift 同源：深蓝对角渐变 + 白色粗壮「Z」+ 右上角三道信号弧。
差异只在底板：mac 图标自带 squircle 和投影（Finder 直接显示图标外形），iOS 则由系统统一
裁圆角，且图标禁止透明（带 alpha 上架会被拒、真机角上会露黑边），所以这里铺满整块正方形。

用法：python ios/tools/make-appicon.py [输出路径]
默认写到 ios/ZCodeRemote/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png。
只在设计改动时需要重跑；CI 直接使用仓库里已生成的 PNG。
"""
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

# 先在 1024 逻辑坐标下对位（与 make-icon.swift 一致），再放大 4 倍绘制后降采样，
# 让 Z 的圆头与信号弧有一致的抗锯齿效果（Swift 用 CoreGraphics 抗锯齿，PIL 只能超采样）。
BASE = 1024
S = 4
W = BASE * S


def background() -> Image.Image:
    """对角渐变。make-icon.swift 里是 CG 坐标（y 向上）(250,860)→(800,160)，
    翻到 PIL（y 向下）即 (250,164)→(800,864)，亮蓝紫在左上、近黑深蓝在右下。"""
    yy, xx = np.mgrid[0:BASE, 0:BASE].astype(np.float32)
    sx, sy = 250.0, 164.0
    ex, ey = 800.0, 864.0
    dx, dy = ex - sx, ey - sy
    t = np.clip(((xx - sx) * dx + (yy - sy) * dy) / (dx * dx + dy * dy), 0.0, 1.0)[..., None]
    c0 = np.array([0.16, 0.22, 0.55], np.float32)  # 顶部亮蓝紫
    c1 = np.array([0.05, 0.06, 0.18], np.float32)  # 底部近黑深蓝
    arr = ((c0 * (1.0 - t) + c1 * t) * 255.0 + 0.5).astype(np.uint8)
    return Image.fromarray(arr, "RGB").resize((W, W), Image.Resampling.BICUBIC)


def z_mask() -> Image.Image:
    """白色「Z」的覆盖层。取点自 make-icon.swift（CG y 向上 → PIL y 向下），
    线段顺序刻意保留顶横 → 斜线 → 左下回勾 → 底横，圆角接头由顶点圆补出。"""
    stroke = [
        (300, 352), (610, 352),  # 上横
        (322, 688),              # 对角线
        (300, 688), (610, 688),  # 下横
    ]
    pts = [(x * S, y * S) for x, y in stroke]
    mask = Image.new("L", (W, W), 0)
    draw = ImageDraw.Draw(mask)
    width = 84 * S
    draw.line(pts, fill=255, width=width, joint="curve")
    r = width // 2
    for x, y in pts:  # 圆头 + 圆角接头
        draw.ellipse([x - r, y - r, x + r, y + r], fill=255)
    return mask


def main() -> None:
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.path.dirname(__file__), "..", "ZCodeRemote", "Assets.xcassets",
        "AppIcon.appiconset", "AppIcon-1024.png",
    )
    img = background().convert("RGBA")
    mask = z_mask()

    # Z 的投影：make-icon.swift 为 offset (0,-14) / blur 26 / 黑蓝 45%；
    # CG 的 -14 在屏幕上朝下，故这里向下偏移 14。
    alpha = mask.filter(ImageFilter.GaussianBlur(26 * S)).point(lambda v: int(v * 0.45))
    shifted = Image.new("L", (W, W), 0)
    shifted.paste(alpha, (0, 14 * S))
    shadow = Image.new("RGBA", (W, W), (0, 0, 26, 0))
    shadow.putalpha(shifted)
    img.alpha_composite(shadow)
    img.paste(Image.new("RGBA", (W, W), (255, 255, 255, 255)), (0, 0), mask)

    # 右上角信号弧 + 圆点（remote 身份符号），三道弧透明度递减。
    # 每道弧先在 L 蒙版上整块填充（外弧线 + 内弧线围成环带，再补两个圆头），
    # 最后整体乘一次透明度——若直接用半透明折线逐段画，段与段的抗锯齿边会叠加出条纹。
    overlay = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)
    cx, cy = 716 * S, 308 * S

    def arc(radius: int, alpha: float) -> None:
        r, half = radius * S, 20 * S
        # CG 的 8°→82° 是 y 向上，翻到 y 向下取负号：点 = (cx + r·cosθ, cy - r·sinθ)
        degs = np.arange(8.0, 82.0 + 0.25, 0.25)
        outer = [(cx + (r + half) * math.cos(math.radians(d)), cy - (r + half) * math.sin(math.radians(d))) for d in degs]
        inner = [(cx + (r - half) * math.cos(math.radians(d)), cy - (r - half) * math.sin(math.radians(d))) for d in degs[::-1]]
        mask = Image.new("L", (W, W), 0)
        md = ImageDraw.Draw(mask)
        md.polygon(outer + inner, fill=255)
        for deg in (8.0, 82.0):  # 圆头端帽
            ex = cx + r * math.cos(math.radians(deg))
            ey = cy - r * math.sin(math.radians(deg))
            md.ellipse([ex - half, ey - half, ex + half, ey + half], fill=255)
        layer = Image.new("RGBA", (W, W), (255, 255, 255, 0))
        layer.putalpha(mask.point(lambda v: int(round(v * alpha))))
        img.alpha_composite(layer)

    arc(78, 0.92)
    arc(132, 0.60)
    arc(186, 0.30)
    dot = 24 * S
    draw.ellipse([cx - dot, cy - dot, cx + dot, cy + dot], fill=(255, 255, 255, 255))
    img.alpha_composite(overlay)

    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    img.convert("RGB").resize((BASE, BASE), Image.Resampling.LANCZOS).save(out, "PNG", optimize=True)
    print(f"written {os.path.abspath(out)}")


if __name__ == "__main__":
    main()
