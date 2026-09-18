package com.netmusic.discstudio.network;

import com.netmusic.discstudio.DiscStudio;
import com.netmusic.discstudio.server.DiscStudioService;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * 客户端 → 服务端：MP4 界面此刻停在哪个视图层。
 *
 * <p>{@code albumQueueIndex >= 0} 表示正打开着队列里这一条（一张专辑）的曲目表；
 * {@code -1} 表示停在队列列表层。服务端只拿它决定<b>自动播完时「随机播放」的范围</b>：
 * 在专辑里就在这张碟内随机换一首，在列表层就在整个队列里随机换一张碟 ——
 * 与界面上「下一曲」按钮的规则保持一致。
 *
 * <p>这不是"控制指令"，改不了任何播放状态；服务端还会核对玩家确实持有这台设备，
 * 所以伪报最多影响自己那台 MP4 的随机范围。
 */
public record Mp4AlbumScopePacket(UUID deviceId, int albumQueueIndex) implements CustomPacketPayload {
    public static final Type<Mp4AlbumScopePacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(DiscStudio.MOD_ID, "mp4_album_scope"));

    public static final StreamCodec<ByteBuf, Mp4AlbumScopePacket> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, Mp4AlbumScopePacket::deviceId,
            ByteBufCodecs.VAR_INT, Mp4AlbumScopePacket::albumQueueIndex,
            Mp4AlbumScopePacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(Mp4AlbumScopePacket payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            context.enqueueWork(() -> DiscStudioService.reportMp4AlbumScope(player, payload));
        }
    }
}
