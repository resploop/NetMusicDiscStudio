package com.netmusic.discstudio.client.cover;

import com.mojang.blaze3d.platform.NativeImage;

/**
 * 把方形专辑封面加工成"光盘"贴图。
 * <p>
 * 在 CPU 上逐像素处理：外缘裁成圆形、中心挖一个孔，都在边界做一像素抗锯齿，
 * 孔外再压一圈略暗的同心环当作盘心。输出是带 alpha 的方形贴图，
 * 渲染时直接贴在一个平面四边形上，形状就来自这里的 alpha 遮罩。
 * <p>
 * 输入刻意是<b>裸的 ARGB 数组</b>而不是 {@code NativeImage}：MC 的
 * {@code NativeImage.read} 只认 PNG（读取前会跑 {@code PngInfo.validateHeader}），
 * 而网易云封面是 JPEG，所以解码交给 {@code ImageIO}，这里只管像素运算。
 */
public final class DiscImageFactory {
    /**
     * 输出贴图边长。
     * <p>
     * 物品栏里实际只有 16px，128 已经足够细腻；再大只是白占显存。
     */
    public static final int SIZE = 128;

    private static final float CENTER = SIZE / 2.0F;
    /** 外缘半径，留 0.5px 让圆边完整落在贴图内。 */
    private static final float OUTER_RADIUS = CENTER - 0.5F;
    /** 中心孔半径。 */
    private static final float INNER_RADIUS = SIZE * 0.10F;
    /** 盘心暗环宽度。 */
    private static final float HUB_RING_WIDTH = SIZE * 0.035F;
    private static final float HUB_RING_DARKEN = 0.72F;
    /** 边缘过渡宽度，用于抗锯齿。 */
    private static final float EDGE_SOFTNESS = 1.0F;

    private DiscImageFactory() {
    }

    /**
     * 生成光盘贴图。
     *
     * @param sourceWidth  封面宽度，至少 1
     * @param sourceHeight 封面高度，至少 1
     * @param sourceArgb   封面像素，ARGB 顺序（alpha 在最高字节），长度为两者之积
     * @return 新建的 128×128 贴图，调用方负责关闭
     */
    public static NativeImage discFrom(int sourceWidth, int sourceHeight, int[] sourceArgb) {
        int coverWidth = Math.max(1, sourceWidth);
        int coverHeight = Math.max(1, sourceHeight);
        NativeImage disc = new NativeImage(NativeImage.Format.RGBA, SIZE, SIZE, true);

        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float dx = x + 0.5F - CENTER;
                float dy = y + 0.5F - CENTER;
                float distance = (float) Math.sqrt(dx * dx + dy * dy);

                int mask = coverage(distance);
                if (mask <= 0) {
                    // 每个像素都显式写一遍，不依赖构造函数是否把缓冲区清零。
                    disc.setPixel(x, y, 0);
                    continue;
                }

                int sourceX = clamp((int) ((x + 0.5F) * coverWidth / SIZE), 0, coverWidth - 1);
                int sourceY = clamp((int) ((y + 0.5F) * coverHeight / SIZE), 0, coverHeight - 1);
                int source = sourceArgb[sourceY * coverWidth + sourceX];

                int alpha = Math.min(mask, (source >>> 24) & 0xFF);
                if (alpha <= 0) {
                    disc.setPixel(x, y, 0);
                    continue;
                }
                int rgb = darken(source & 0x00FFFFFF, distance);
                disc.setPixel(x, y, (alpha << 24) | rgb);
            }
        }
        return disc;
    }

    /** 距离 → alpha 遮罩；圆外与孔内为 0。 */
    private static int coverage(float distance) {
        float outer = OUTER_RADIUS - distance;
        float inner = distance - INNER_RADIUS;
        float edge = Math.min(outer, inner);
        if (edge >= EDGE_SOFTNESS) {
            return 255;
        }
        if (edge <= 0.0F) {
            return 0;
        }
        return (int) (edge / EDGE_SOFTNESS * 255.0F);
    }

    /** 盘心孔外那一圈压暗，让"孔"在缩到 16px 时也看得出来。 */
    private static int darken(int rgb, float distance) {
        if (distance > INNER_RADIUS + HUB_RING_WIDTH) {
            return rgb;
        }
        int r = (int) (((rgb >> 16) & 0xFF) * HUB_RING_DARKEN);
        int g = (int) (((rgb >> 8) & 0xFF) * HUB_RING_DARKEN);
        int b = (int) ((rgb & 0xFF) * HUB_RING_DARKEN);
        return (r << 16) | (g << 8) | b;
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }
}
