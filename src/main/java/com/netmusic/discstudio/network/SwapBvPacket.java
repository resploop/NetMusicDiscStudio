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
 * 客户端 → 服务端：就地改写唱片机槽位里「可擦写网络唱片」的 BV 号。
 * <p>
 * 这是唯一一条绕过唱片刻录机的写入路径，因为「换一个视频试试」的即时性
 * 在唱片机上体验最好。网络CD 没有对应能力——整张曲目表只能在刻录机里刻。
 */
public record SwapBvPacket(BlockPos pos, String input, int page) implements CustomPacketPayload {
    private static final int MAX_INPUT_LENGTH = 512;

    public static final Type<SwapBvPacket> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(DiscStudio.MOD_ID, "swap_bv"));

    public static final StreamCodec<ByteBuf, SwapBvPacket> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, SwapBvPacket::pos,
            ByteBufCodecs.STRING_UTF8, SwapBvPacket::input,
            ByteBufCodecs.VAR_INT, SwapBvPacket::page,
            SwapBvPacket::new);

    public SwapBvPacket {
        pos = pos.immutable();
        input = input.length() > MAX_INPUT_LENGTH ? input.substring(0, MAX_INPUT_LENGTH) : input;
        page = Math.max(1, Math.min(page, 9999));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SwapBvPacket payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            // 方块实体状态必须在服务端主线程读取，B 站请求随后才丢到工作线程。
            context.enqueueWork(() -> DiscStudioService.swapBv(player, payload));
        }
    }
}
