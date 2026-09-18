package com.netmusic.discstudio.client.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.serialization.MapCodec;
import com.netmusic.discstudio.DiscStudio;
import com.netmusic.discstudio.client.cover.AlbumCoverTextures;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;

/**
 * 「网络CD」刻录后使用的特殊物品模型：把专辑封面画成一张光盘。
 * <p>
 * 贴图本身在 {@link AlbumCoverTextures} 里离线加工（裁圆、挖中心孔），
 * 这里只负责把一张带 alpha 的方形贴图贴在物品平面上——"光盘"的形状完全来自贴图的 alpha，
 * 不需要额外几何。
 * <p>
 * 坐标约定：{@code FaceBakery} 在烘焙模型时已经把 0..16 的模型坐标除以 16，
 * 所以 {@code submit} 拿到的位姿里，物品平面是 x/y ∈ [0,1]、z ∈ [7.5/16, 8.5/16]，
 * 与 {@code item/generated} 的扁平物品完全重合。提交正反两面，从背后看也正常。
 * <p>
 * 封面还没下载完或下载失败时退回 {@link #FALLBACK_TEXTURE}，也就是本模组自己那张
 * 半透明彩虹光盘贴图——与未刻录的空白盘是同一张，几何（外缘 0.496、轴孔 0.10、
 * 盘心暗环 0.035）也与 {@code DiscImageFactory} 生成的封面盘完全一致，所以封面下载完成
 * 的那一刻物品只是"图案换了"，轮廓和大小不会跳变。
 */
public class AlbumCoverSpecialRenderer implements SpecialModelRenderer<Identifier> {
    /**
     * 扁平物品两个面的位置，与 {@code ItemModelGenerator} 给 {@code item/generated} 用的
     * {@code z = 7.5..8.5} 完全一致（模型坐标在烘焙时已除以 16，所以这里是 0.469 / 0.531）。
     * <p>
     * {@code SOUTH} 是物品正面（朝 +Z，也就是 {@code FaceBakery} 里 {@code MAX_Z} 那一面），
     * {@code NORTH} 是背面。
     */
    private static final float Z_SOUTH = 8.5F / 16.0F;
    private static final float Z_NORTH = 7.5F / 16.0F;

    /**
     * 封面不可用时的兜底贴图：本模组自己的半透明彩虹光盘，而不是上游的
     * {@code netmusic:item/music_cd}。
     * <p>
     * 用自己这张的理由有三个：
     * <ol>
     *   <li>上游那张压根不是圆盘，是"带红标签的斜视小 CD 图标"，混在圆盘里很出戏；</li>
     *   <li>本模组的刻录盘本来就是"彩虹光盘 + 封面"，兜底用同一套视觉语言才连贯；</li>
     *   <li>几何逐项对齐 {@code DiscImageFactory}（外缘 0.496 / 轴孔 0.10 / 盘心暗环 0.035），
     *       封面到位前后物品轮廓不跳变。</li>
     * </ol>
     * 纹理走的是<b>原始路径</b>而不是图集坐标——{@code RenderTypes.itemTranslucent} 会把
     * 这个 id 直接绑到 {@code Sampler0}（与玩家皮肤同一个机制），所以 UV 0..1 正好铺满整张图。
     */
    private static final Identifier FALLBACK_TEXTURE =
            Identifier.fromNamespaceAndPath(DiscStudio.MOD_ID, "textures/item/network_cd.png");

    /** 实际注册的模型类型 id，注册与使用两处共用。 */
    public static final Identifier MODEL_ID =
            Identifier.fromNamespaceAndPath(DiscStudio.MOD_ID, "album_cover");

    @Override
    public @Nullable Identifier extractArgument(ItemStack stack) {
        // 未就绪时返回 null，等贴图到位后参数变化会让上游重建物品的渲染状态。
        return AlbumCoverTextures.textureForDisc(stack);
    }

    @Override
    public void submit(@Nullable Identifier texture, PoseStack poseStack,
                       SubmitNodeCollector submitNodeCollector, int lightCoords, int overlayCoords,
                       boolean hasFoil, int outlineColor) {
        RenderType renderType = RenderTypes.itemTranslucent(texture == null ? FALLBACK_TEXTURE : texture);
        submitNodeCollector.submitCustomGeometry(poseStack, renderType, (pose, buffer) -> {
            // 顶点顺序与 UV 分配逐字照抄 FaceBakery 的 {@code FaceInfo.SOUTH}：
            // 从 +Z 看是逆时针，所以这一面朝外。
            vertex(buffer, pose, 0.0F, 1.0F, Z_SOUTH, 0.0F, 0.0F, LIGHT_SOUTH, lightCoords, overlayCoords);
            vertex(buffer, pose, 0.0F, 0.0F, Z_SOUTH, 0.0F, 1.0F, LIGHT_SOUTH, lightCoords, overlayCoords);
            vertex(buffer, pose, 1.0F, 0.0F, Z_SOUTH, 1.0F, 1.0F, LIGHT_SOUTH, lightCoords, overlayCoords);
            vertex(buffer, pose, 1.0F, 1.0F, Z_SOUTH, 1.0F, 0.0F, LIGHT_SOUTH, lightCoords, overlayCoords);

            // 背面照抄 {@code FaceInfo.NORTH}：绕序相反、UV 也跟着镜像，
            // 于是从背后看到的图案和正版扁平物品一样是左右翻过来的。
            vertex(buffer, pose, 1.0F, 1.0F, Z_NORTH, 1.0F, 0.0F, LIGHT_NORTH, lightCoords, overlayCoords);
            vertex(buffer, pose, 1.0F, 0.0F, Z_NORTH, 1.0F, 1.0F, LIGHT_NORTH, lightCoords, overlayCoords);
            vertex(buffer, pose, 0.0F, 0.0F, Z_NORTH, 0.0F, 1.0F, LIGHT_NORTH, lightCoords, overlayCoords);
            vertex(buffer, pose, 0.0F, 1.0F, Z_NORTH, 0.0F, 0.0F, LIGHT_NORTH, lightCoords, overlayCoords);
        });
    }

    /** 正面朝 +Z、背面朝 -Z 的法线，渲染器的法线参数要按面给，不能两个面共用一个。 */
    private static final float[] LIGHT_SOUTH = {0.0F, 0.0F, 1.0F};
    private static final float[] LIGHT_NORTH = {0.0F, 0.0F, -1.0F};

    private static void vertex(VertexConsumer buffer, PoseStack.Pose pose, float x, float y, float z,
                               float u, float v, float[] normal, int lightCoords, int overlayCoords) {
        buffer.addVertex(pose, x, y, z)
                .setColor(0xFFFFFFFF)
                .setUv(u, v)
                .setOverlay(overlayCoords)
                .setLight(lightCoords)
                .setNormal(pose, normal[0], normal[1], normal[2]);
    }

    @Override
    public void getExtents(Consumer<Vector3fc> output) {
        // 上游用它算物品实体的厚度与包围盒，给扁平盒子的八个角即可。
        for (float z : new float[]{Z_SOUTH, Z_NORTH}) {
            output.accept(new Vector3f(0.0F, 0.0F, z));
            output.accept(new Vector3f(1.0F, 0.0F, z));
            output.accept(new Vector3f(0.0F, 1.0F, z));
            output.accept(new Vector3f(1.0F, 1.0F, z));
        }
    }

    /** 无参的模型类型；JSON 里写 {@code {"type": "netmusic_disc_studio:album_cover"}}。 */
    public record Unbaked() implements SpecialModelRenderer.Unbaked<Identifier> {
        public static final Unbaked INSTANCE = new Unbaked();
        public static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(INSTANCE);

        @Override
        public MapCodec<Unbaked> type() {
            return MAP_CODEC;
        }

        @Override
        public SpecialModelRenderer<Identifier> bake(SpecialModelRenderer.BakingContext context) {
            return new AlbumCoverSpecialRenderer();
        }
    }
}
