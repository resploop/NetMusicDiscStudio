package com.netmusic.discstudio.network;

import com.netmusic.discstudio.DiscStudio;
import com.netmusic.discstudio.server.DiscStudioService;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：在唱片刻录机里把一张「网络CD」刻成整张专辑/歌单。
 * <p>
 * 只传玩家输入的原文，网易云请求与结果写入都在服务端完成。
 * 这样做有两个好处：玩家无法伪造出网易云没返回的曲目；
 * 而且上游的 {@code SetMusicIDMessage} 只能携带一条 {@code SongInfo}，
 * 装不下整张曲目表。
 */
public record BurnAlbumPacket(String input) implements CustomPacketPayload {
    private static final int MAX_INPUT_LENGTH = 512;

    public static final Type<BurnAlbumPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(DiscStudio.MOD_ID, "burn_album"));

    public static final StreamCodec<ByteBuf, BurnAlbumPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, BurnAlbumPacket::input,
            BurnAlbumPacket::new);

    public BurnAlbumPacket {
        input = input.length() > MAX_INPUT_LENGTH ? input.substring(0, MAX_INPUT_LENGTH) : input;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BurnAlbumPacket payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            context.enqueueWork(() -> DiscStudioService.burnAlbum(player, payload.input()));
        }
    }
}
