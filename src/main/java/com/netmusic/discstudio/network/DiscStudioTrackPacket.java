package com.netmusic.discstudio.network;

import com.netmusic.discstudio.DiscStudio;
import com.netmusic.discstudio.server.DiscStudioService;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：网络CD的选曲与切歌。
 * <p>
 * 只传下标和方向，真正的曲目信息由服务端从唱片自己的 {@code album_playlist} 组件里取，
 * 客户端无法伪造出唱片里没有的歌曲。
 */
public record DiscStudioTrackPacket(BlockPos pos, TrackAction action, int index)
        implements CustomPacketPayload {
    public static final Type<DiscStudioTrackPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(DiscStudio.MOD_ID, "disc_track"));

    private static final StreamCodec<ByteBuf, TrackAction> ACTION_CODEC = new StreamCodec<>() {
        @Override
        public TrackAction decode(ByteBuf buffer) {
            return TrackAction.byId(ByteBufCodecs.VAR_INT.decode(buffer));
        }

        @Override
        public void encode(ByteBuf buffer, TrackAction value) {
            ByteBufCodecs.VAR_INT.encode(buffer, value.id());
        }
    };

    public static final StreamCodec<ByteBuf, DiscStudioTrackPacket> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, DiscStudioTrackPacket::pos,
            ACTION_CODEC, DiscStudioTrackPacket::action,
            ByteBufCodecs.VAR_INT, DiscStudioTrackPacket::index,
            DiscStudioTrackPacket::new);

    public DiscStudioTrackPacket {
        pos = pos.immutable();
        index = Math.max(0, index);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(DiscStudioTrackPacket payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            context.enqueueWork(() -> DiscStudioService.controlTrack(player, payload));
        }
    }

    public enum TrackAction {
        /** 直接跳到 {@code index} 指定的曲目。 */
        SELECT,
        /** 下一曲。 */
        NEXT,
        /** 上一曲。 */
        PREV;

        public int id() {
            return ordinal();
        }

        public static TrackAction byId(int id) {
            TrackAction[] values = values();
            return id >= 0 && id < values.length ? values[id] : SELECT;
        }
    }
}
