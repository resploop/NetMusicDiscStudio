package com.netmusic.discstudio;

import com.github.tartaricacid.netmusic.api.resolver.MusicPlayResolverManager;
import com.mojang.logging.LogUtils;
import com.netmusic.discstudio.bili.NetEaseDirectResolver;
import com.netmusic.discstudio.init.ModDataComponents;
import com.netmusic.discstudio.init.ModItems;
import com.netmusic.discstudio.init.ModNetwork;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

/**
 * NetMusic Disc Studio —— NetMusic + NetMusicCanPlayBili 的附属模组。
 * <p>
 * 提供两件物品：
 * <ul>
 *   <li>「可擦写网络唱片」：放入现代化唱片机后可随时改播任意 B 站 BV/av，不必反复刻录；</li>
 *   <li>「网络CD」：把整张网易云专辑/歌单写进一张唱片，支持选曲与上一曲/下一曲。</li>
 * </ul>
 * 两者都复用上游既有链路：物品自带 {@code netmusic:song_info}，唱片机照常走
 * {@link MusicPlayResolverManager} 解析播放，本模组只负责"写盘"和"换曲"。
 */
@Mod(DiscStudio.MOD_ID)
public class DiscStudio {
    public static final String MOD_ID = "netmusic_disc_studio";
    public static final Logger LOGGER = LogUtils.getLogger();

    public DiscStudio(IEventBus modEventBus, ModContainer modContainer) {
        ModDataComponents.DATA_COMPONENTS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModItems.TABS.register(modEventBus);
        modEventBus.addListener(ModNetwork::register);
        modEventBus.addListener(DiscStudioConfig::onLoad);

        modContainer.registerConfig(ModConfig.Type.COMMON, DiscStudioConfig.SPEC);

        // 网易云直链本身由 NetMusic 客户端 handler 播放；这里注册一个"透明放行"解析器，
        // 让现代化唱片机的 VIP 判定能通过，行为与 NetMusic 自己的播放机保持一致。
        MusicPlayResolverManager.registerResolver(new NetEaseDirectResolver());
    }
}
