package com.netmusic.discstudio.init;

import com.netmusic.discstudio.DiscStudio;
import com.netmusic.discstudio.network.BurnAlbumPacket;
import com.netmusic.discstudio.network.DiscStudioTrackPacket;
import com.netmusic.discstudio.network.SwapBvPacket;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class ModNetwork {
    private static final String VERSION = "1";

    private ModNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        // 刻录机：把「网络CD」刻成整张专辑/歌单
        registrar.playToServer(
                BurnAlbumPacket.TYPE,
                BurnAlbumPacket.STREAM_CODEC,
                BurnAlbumPacket::handle);
        // 唱片机：改写「可擦写网络唱片」的 BV
        registrar.playToServer(
                SwapBvPacket.TYPE,
                SwapBvPacket.STREAM_CODEC,
                SwapBvPacket::handle);
        // 唱片机：网络CD 选曲 / 上一曲 / 下一曲
        registrar.playToServer(
                DiscStudioTrackPacket.TYPE,
                DiscStudioTrackPacket.STREAM_CODEC,
                DiscStudioTrackPacket::handle);
    }
}
