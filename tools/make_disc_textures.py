"""生成 NetMusicDiscStudio 的两张物品贴图。

产物（32x32 RGBA，写进 src/main/resources）：
  * item/network_cd.png            —— 未刻录的网络 CD：刻录后同款盘形 + 半透明彩虹光面
  * item/erasable_network_disc.png —— 可擦写网络唱片：黑胶唱盘

几何刻意与 client/cover/DiscImageFactory.java 对齐（外缘半径 0.496、中心孔 0.10、
盘心暗环 0.035），这样"未刻录的 CD"与"刻录后由封面加工出来的光盘"轮廓完全一致。

做法：在 8 倍超采样画布上逐像素算，最后面积平均缩回 32x32，得到自带抗锯齿的圆边。
改完直接重跑本脚本即可，不要在外部手改 PNG。

用法：
    python tools/make_disc_textures.py            # 只写贴图
    python tools/make_disc_textures.py --preview  # 另外在 tools/preview/ 出对比预览图
"""

from __future__ import annotations

import argparse
import colorsys
import math
import os

from PIL import Image

OUT_SIZE = 32
SUPERSAMPLE = 8
CANVAS = OUT_SIZE * SUPERSAMPLE
CENTER = CANVAS / 2.0
# 1 个纹理像素 = 1 个超采样像素；1 个游戏显示像素 = 2 个纹理像素
SCALE = float(SUPERSAMPLE)

HERE = os.path.dirname(os.path.abspath(__file__))
TEXTURE_DIR = os.path.join(HERE, "..", "src", "main", "resources",
                           "assets", "netmusic_disc_studio", "textures", "item")
PREVIEW_DIR = os.path.join(HERE, "preview")

# CD 盘面上被"穿过"的底色。透明像素也写这个颜色，避免缩放时把边缘往黑里拉。
CD_EDGE_COLOR = (232, 236, 242)
VINYL_EDGE_COLOR = (16, 14, 19)


def smoothstep(edge0: float, edge1: float, x: float) -> float:
    """标准 smoothstep；edge0 == edge1 时退化成阶跃。"""
    if edge0 == edge1:
        return 1.0 if x >= edge1 else 0.0
    t = (x - edge0) / (edge1 - edge0)
    t = 0.0 if t < 0.0 else (1.0 if t > 1.0 else t)
    return t * t * (3.0 - 2.0 * t)


def mix(a: tuple[float, float, float], b: tuple[float, float, float],
        t: float) -> tuple[float, float, float]:
    return (a[0] + (b[0] - a[0]) * t,
            a[1] + (b[1] - a[1]) * t,
            a[2] + (b[2] - a[2]) * t)


def scale_rgb(c: tuple[float, float, float], k: float) -> tuple[float, float, float]:
    return (c[0] * k, c[1] * k, c[2] * k)


def clamp255(v: float) -> int:
    return 0 if v < 0.0 else (255 if v > 255.0 else int(v + 0.5))


def build_network_cd() -> Image.Image:
    """未刻录的网络 CD：银白底 + 绕盘螺旋的彩虹光谱 + 斜向高光 + 盘心孔。"""
    img = Image.new("RGBA", (CANVAS, CANVAS))
    px = img.load()

    # 与 DiscImageFactory 同比例：半径 0.496、中心孔 0.10、盘心暗环 0.035
    r_outer = 0.496 * CANVAS
    r_hole = 0.100 * CANVAS
    r_hub = r_hole + 0.035 * CANVAS
    r_rim = r_outer - 0.024 * CANVAS          # 外圈压暗的起点

    # 高光带方向：从左上打光
    hl_angle = math.radians(-128.0)
    hl_cos, hl_sin = math.cos(hl_angle), math.sin(hl_angle)

    base_color = (214.0, 222.0, 231.0)

    for y in range(CANVAS):
        for x in range(CANVAS):
            dx = x + 0.5 - CENTER
            dy = y + 0.5 - CENTER
            d = math.hypot(dx, dy)

            # 圆形遮罩：外缘与中心孔各留约 0.6 纹理像素的软边
            mask = (smoothstep(r_outer, r_outer - 0.6 * SCALE, d)
                    * smoothstep(r_hole, r_hole + 0.6 * SCALE, d))
            if mask <= 0.002:
                px[x, y] = (*CD_EDGE_COLOR, 0)
                continue

            t = (d - r_hole) / (r_outer - r_hole)      # 归一化半径，0 = 孔边，1 = 盘沿
            t = 0.0 if t < 0.0 else (1.0 if t > 1.0 else t)
            angle = math.atan2(dy, dx)

            # 彩虹：绕盘两周光谱，再让相位随半径推进半圈多，形成螺旋彩带。
            # 现实中光盘的衍射色就是这种"扇形 + 同心环"叠加的样子。
            hue = (angle / (2.0 * math.pi) + 0.05) * 2.0 + d / (5.2 * CANVAS)
            hue -= math.floor(hue)
            rr, gg, bb = colorsys.hsv_to_rgb(hue, 0.55, 1.0)
            rainbow = (rr * 255.0, gg * 255.0, bb * 255.0)

            # 中段彩虹最强，靠近孔与外沿收敛回银色
            strength = (0.78
                        * smoothstep(0.0, 0.26, t)
                        * (1.0 - 0.40 * smoothstep(0.74, 1.0, t)))
            color = mix(base_color, rainbow, strength)

            # 极细的同心纹路，模拟数据轨
            color = scale_rgb(color, 0.965 + 0.035 * math.cos(d / (0.42 * SCALE)))

            # 斜向镜面高光：一条宽带 + 一条更细更亮的窄带
            along = dx * hl_cos + dy * hl_sin
            across = -dx * hl_sin + dy * hl_cos
            falloff = smoothstep(r_outer, r_outer * 0.10, d)
            glow = math.exp(-((across - 0.30 * r_outer) ** 2) / (2.0 * (0.17 * r_outer) ** 2))
            glow += 0.70 * math.exp(-((across + 0.34 * r_outer) ** 2) / (2.0 * (0.075 * r_outer) ** 2))
            glow *= falloff
            color = tuple(min(255.0, color[i] + 66.0 * glow) for i in range(3))

            # 外沿压暗一圈，缩到 16px 时盘子边界才立得住
            color = scale_rgb(color, 1.0 - 0.24 * smoothstep(r_rim, r_outer, d))

            # 盘心：孔外先描一道细暗环，再把整个盘心区压暗
            if d <= r_hub:
                color = scale_rgb(color, 0.74)
            color = scale_rgb(color, 1.0 - 0.40 * math.exp(
                -((d - r_hole) ** 2) / (2.0 * (0.30 * SCALE) ** 2)))

            # 半透明盘面：整体 80% 不透明度，透过它能隐约看见背后的格子
            px[x, y] = (clamp255(color[0]), clamp255(color[1]), clamp255(color[2]),
                        int(round(mask * 205.0)))

    return img.resize((OUT_SIZE, OUT_SIZE), Image.BOX)


def build_erasable_network_disc() -> Image.Image:
    """可擦写网络唱片：黑胶盘面 + 密纹 + 琥珀色标签 + 斜向光泽。"""
    img = Image.new("RGBA", (CANVAS, CANVAS))
    px = img.load()

    r_outer = 0.494 * CANVAS
    r_label = 0.215 * CANVAS          # 标签半径（现实约 0.33，缩小些免得像 CD 的盘心）
    r_label_edge = r_label + 0.010 * CANVAS
    r_hole = 0.030 * CANVAS
    r_lead_in = 0.432 * CANVAS        # 导入槽：靠近盘沿的那道亮圈
    r_run_out = 0.240 * CANVAS        # 导出槽：紧贴标签外圈

    label_body = (240.0, 180.0, 70.0)
    label_deep = (188.0, 120.0, 32.0)
    label_line = (158.0, 96.0, 24.0)
    label_rim = (252.0, 232.0, 178.0)
    label_edge = (16.0, 13.0, 18.0)

    # 纯黑在物品槽里会糊成一坨，用偏紫的炭黑做盘心、近黑做盘沿
    vinyl_hub = (32.0, 28.0, 40.0)
    vinyl_rim = (11.0, 10.0, 15.0)

    hl_angle = math.radians(-132.0)
    hl_cos, hl_sin = math.cos(hl_angle), math.sin(hl_angle)

    for y in range(CANVAS):
        for x in range(CANVAS):
            dx = x + 0.5 - CENTER
            dy = y + 0.5 - CENTER
            d = math.hypot(dx, dy)

            mask = 1.0 - smoothstep(r_outer - 0.7 * SCALE, r_outer + 0.5 * SCALE, d)
            if mask <= 0.002:
                px[x, y] = (*VINYL_EDGE_COLOR, 0)
                continue

            t = min(1.0, d / r_outer)

            # 盘面：径向渐变 + 左上打光的线性渐变，纯平的黑会显得很"塑料"
            color = mix(vinyl_hub, vinyl_rim, t ** 1.1)
            color = tuple(max(0.0, color[i] + 10.0 * (1.0 - t) - 5.0 * (dx + dy) / r_outer)
                          for i in range(3))

            # 密纹：分两层。极细的连续纹路只给一点"丝光"，真正让盘子像黑胶的是下面
            # 几道离散的槽圈——周期取 2 个显示像素，缩到 16px 刚好不糊。
            color = scale_rgb(color, 0.975 + 0.025 * math.cos(2.0 * math.pi * d / (0.125 * CANVAS)))
            for groove_t, groove_gain in ((0.44, 32.0), (0.55, 24.0), (0.66, 32.0),
                                          (0.77, 22.0), (0.88, 34.0)):
                band = math.exp(-((d - groove_t * r_outer) ** 2) / (2.0 * (0.020 * CANVAS) ** 2))
                color = tuple(min(255.0, color[i] + groove_gain * band) for i in range(3))

            # 导入槽 / 导出槽
            for ring_r, ring_w, ring_gain in ((r_lead_in, 0.014, 30.0), (r_run_out, 0.011, 22.0)):
                band = math.exp(-((d - ring_r) ** 2) / (2.0 * (ring_w * CANVAS) ** 2))
                color = tuple(min(255.0, color[i] + ring_gain * band) for i in range(3))

            # 盘沿一道极细的暗线，浅色底上盘子的轮廓才不会糊掉
            edge_line = math.exp(-((d - 0.985 * r_outer) ** 2) / (2.0 * (0.008 * CANVAS) ** 2))
            color = tuple(max(0.0, color[i] - 20.0 * edge_line) for i in range(3))

            # 黑胶的斜向光泽。关键是"大部分还是黑的，只有一条带子亮起来"：
            # 大面积低幅度的整体提亮 + 一条窄而亮的反光带 + 一条细高光 + 盘沿一线反光
            across = -dx * hl_sin + dy * hl_cos
            sheen_color = mix((56.0, 62.0, 94.0), (80.0, 66.0, 44.0), (across / r_outer + 1.0) * 0.5)
            broad = math.exp(-((across - 0.05 * r_outer) ** 2) / (2.0 * (0.60 * r_outer) ** 2))
            color = tuple(min(255.0, color[i] + sheen_color[i] * broad * 0.16) for i in range(3))
            sheen = math.exp(-((across - 0.34 * r_outer) ** 2) / (2.0 * (0.115 * r_outer) ** 2))
            color = tuple(min(255.0, color[i] + sheen_color[i] * sheen * 1.10) for i in range(3))
            streak = math.exp(-((across - 0.50 * r_outer) ** 2) / (2.0 * (0.052 * r_outer) ** 2))
            streak *= smoothstep(r_outer, r_outer * 0.15, d)
            color = tuple(min(255.0, color[i] + 40.0 * streak) for i in range(3))
            rim_catch = math.exp(-((d - r_outer * 0.960) ** 2) / (2.0 * (0.010 * CANVAS) ** 2))
            rim_catch *= smoothstep(-0.95 * r_outer, -0.45 * r_outer, across)
            color = tuple(min(255.0, color[i] + 30.0 * rim_catch) for i in range(3))

            # 标签：外圈一道暗描边，盘面是琥珀金渐变，内缘压一道浅色细圈
            if d <= r_label_edge:
                color = label_edge
            if d <= r_label:
                label_t = d / r_label
                color = mix(label_body, label_deep, label_t ** 1.4)
                band = math.exp(-((label_t - 0.58) ** 2) / (2.0 * 0.075 ** 2))
                color = mix(color, label_line, 0.70 * band)
                # 标签内缘一道浅色细圈，缩到 16px 时标签边界才清晰
                ring = math.exp(-((label_t - 0.90) ** 2) / (2.0 * 0.055 ** 2))
                color = mix(color, label_rim, 0.70 * ring)
                # 标签上的一点高光，别让它是块死板的色饼
                label_glow = math.exp(-((d - 0.34 * r_label) ** 2) / (2.0 * (0.46 * r_label) ** 2))
                color = tuple(min(255.0, color[i] + 34.0 * label_glow * (0.45 + 0.55 * sheen))
                              for i in range(3))

            # 中心轴孔
            if d <= r_hole:
                px[x, y] = (*VINYL_EDGE_COLOR, 0)
                continue

            px[x, y] = (clamp255(color[0]), clamp255(color[1]), clamp255(color[2]),
                        int(round(mask * 255.0)))

    return img.resize((OUT_SIZE, OUT_SIZE), Image.BOX)


def make_preview(image: Image.Image, background: tuple[int, int, int], zoom: int = 12) -> Image.Image:
    """模拟游戏里 16px 槽位的样子：缩到 16px 再放大回来，垫在给定底色上。"""
    small = image.resize((16, 16), Image.LANCZOS)
    canvas = Image.new("RGBA", (16, 16), (*background, 255))
    canvas.alpha_composite(small)
    return canvas.resize((16 * zoom, 16 * zoom), Image.NEAREST)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--preview", action="store_true", help="额外输出放大预览图")
    args = parser.parse_args()

    os.makedirs(TEXTURE_DIR, exist_ok=True)

    textures = {
        "network_cd": build_network_cd(),
        "erasable_network_disc": build_erasable_network_disc(),
    }

    for name, image in textures.items():
        path = os.path.normpath(os.path.join(TEXTURE_DIR, name + ".png"))
        image.save(path)
        print(f"写入 {path}  ({image.width}x{image.height})")

    if not args.preview:
        return

    os.makedirs(PREVIEW_DIR, exist_ok=True)
    # 两种底色：普通物品槽灰、暗底。半透明盘面在这两种背景下应当明显不同。
    for name, image in textures.items():
        tiles = [make_preview(image, (139, 139, 139)), make_preview(image, (30, 30, 34))]
        sheet = Image.new("RGBA", (tiles[0].width * 2 + 24, tiles[0].height), (0, 0, 0, 0))
        sheet.alpha_composite(tiles[0], (0, 0))
        sheet.alpha_composite(tiles[1], (tiles[0].width + 24, 0))
        path = os.path.normpath(os.path.join(PREVIEW_DIR, name + "_preview.png"))
        sheet.save(path)
        print(f"预览 {path}")

    # 一张并排的 4 倍原始图，方便看细节
    raw = Image.new("RGBA", (OUT_SIZE * 2 + 16, OUT_SIZE), (255, 255, 255, 255))
    raw.alpha_composite(textures["network_cd"], (0, 0))
    raw.alpha_composite(textures["erasable_network_disc"], (OUT_SIZE + 16, 0))
    path = os.path.normpath(os.path.join(PREVIEW_DIR, "raw_pair.png"))
    raw.resize((raw.width * 10, raw.height * 10), Image.NEAREST).save(path)
    print(f"预览 {path}")


if __name__ == "__main__":
    main()
