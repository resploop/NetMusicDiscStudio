package com.netmusic.discstudio;

import com.netmusic.discstudio.client.model.AlbumCoverSpecialRenderer;
import com.netmusic.discstudio.client.netease.NetEaseSession;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterSpecialModelRendererEvent;

/**
 * 客户端入口。做两件事：
 * <ol>
 *   <li>把「专辑封面光盘」这个特殊物品模型注册进上游的 id 映射表。
 *       上游在 {@code SpecialModelRenderers.bootstrap()} 里通过
 *       {@link RegisterSpecialModelRendererEvent} 把注册机会抛给模组，
 *       之后 {@code assets/netmusic_disc_studio/items/network_cd.json} 里就能用
 *       {@code netmusic_disc_studio:album_cover} 这个模型类型。</li>
 *   <li>把磁盘上的网易云登录态注入 NetMusic 的 WebApi。放在客户端 setup 阶段而不是构造函数里，
 *       是因为那时 NetMusic 的 {@code NET_EASE_WEB_API} 才一定已经建好；
 *       没有这一步，VIP 歌曲的音频请求依旧不带 Cookie。</li>
 * </ol>
 * 这个类只在物理客户端加载（{@code dist = Dist.CLIENT}），
 * 因此引用客户端专属的事件类不会影响服务端。
 */
@Mod(value = DiscStudio.MOD_ID, dist = Dist.CLIENT)
public class DiscStudioClient {
    public DiscStudioClient(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(DiscStudioClient::registerSpecialModelRenderers);
        modEventBus.addListener(DiscStudioClient::onClientSetup);
    }

    private static void registerSpecialModelRenderers(RegisterSpecialModelRendererEvent event) {
        event.register(AlbumCoverSpecialRenderer.MODEL_ID, AlbumCoverSpecialRenderer.Unbaked.MAP_CODEC);
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        NetEaseSession.apply();
    }
}
