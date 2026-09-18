package com.netmusic.discstudio.init;

import com.netmusic.discstudio.DiscStudio;
import com.netmusic.discstudio.item.ErasableNetworkDiscItem;
import com.netmusic.discstudio.item.NetworkCdItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(DiscStudio.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, DiscStudio.MOD_ID);

    /** 可擦写网络唱片：一张空盘，可以在唱片机里随时改写 BV 号。 */
    public static final DeferredItem<ErasableNetworkDiscItem> ERASABLE_NETWORK_DISC =
            ITEMS.register("erasable_network_disc", ErasableNetworkDiscItem::new);

    /** 网络CD：写入整张网易云专辑/歌单，支持选曲与切歌。 */
    public static final DeferredItem<NetworkCdItem> NETWORK_CD =
            ITEMS.register("network_cd", NetworkCdItem::new);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> DISC_STUDIO_TAB = TABS.register(
            "disc_studio",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.netmusic_disc_studio"))
                    .icon(() -> new ItemStack(ERASABLE_NETWORK_DISC.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(new ItemStack(ERASABLE_NETWORK_DISC.get()));
                        output.accept(new ItemStack(NETWORK_CD.get()));
                    })
                    .build());

    private ModItems() {
    }
}
