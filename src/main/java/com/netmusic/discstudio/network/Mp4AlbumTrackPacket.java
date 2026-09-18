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
 * 客户端 → 服务端：MP4 里某张「网络CD」切到第几首。
 *
 * <p>一张网络CD在 MP4 队列里只占一条，"播到第几首"记在这条物品自己的
 * {@code album_playlist} 组件里。物品 NBT 只有服务端说了算，所以换歌必须走这条包：
 * 服务端把 {@code queueIndex} 那一项的曲目下标改成 {@code trackIndex}，
 * 必要时再把播放重启到这一条。
 *
 * <p>客户端只能指定下标，曲目内容由服务端从碟自己的曲目表里取，
 * 伪造不出碟里没有的歌。
 */
public record Mp4AlbumTrackPacket(UUID deviceId, int queueIndex, int trackIndex, boolean restart)
        implements CustomPacketPayload {
    public static final Type<Mp4AlbumTrackPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(DiscStudio.MOD_ID, "mp4_album_track"));

    public static final StreamCodec<ByteBuf, Mp4AlbumTrackPacket> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, Mp4AlbumTrackPacket::deviceId,
            ByteBufCodecs.VAR_INT, Mp4AlbumTrackPacket::queueIndex,
            ByteBufCodecs.VAR_INT, Mp4AlbumTrackPacket::trackIndex,
            ByteBufCodecs.BOOL, Mp4AlbumTrackPacket::restart,
            Mp4AlbumTrackPacket::new);

    public Mp4AlbumTrackPacket {
        queueIndex = Math.max(0, queueIndex);
        trackIndex = Math.max(0, trackIndex);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(Mp4AlbumTrackPacket payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            context.enqueueWork(() -> DiscStudioService.controlMp4Album(player, payload));
        }
    }
}
