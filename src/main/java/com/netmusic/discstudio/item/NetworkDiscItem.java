package com.netmusic.discstudio.item;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.netmusic.discstudio.disc.NetworkDiscs;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.function.Consumer;

/**
 * 本模组两件唱片物品的共同基类。
 * <p>
 * 它们与 NetMusic 原版 {@code netmusic:music_cd} 的区别只有一个：
 * 内容可以在唱片机里反复改写，因此都不堆叠，并且名称直接跟随当前曲目。
 */
@SuppressWarnings("deprecation")
public abstract class NetworkDiscItem extends Item {
    protected NetworkDiscItem(Identifier id) {
        super(new Properties().setId(ResourceKey.create(Registries.ITEM, id)).stacksTo(1));
    }

    /** 物品使用方式的翻译键，追加在 tooltip 末尾。 */
    protected abstract String usageKey();

    @Override
    public Component getName(ItemStack stack) {
        ItemMusicCD.SongInfo info = NetworkDiscs.songInfo(stack);
        if (info == null || info.songName == null || info.songName.isBlank()) {
            return Component.translatable("item.netmusic_disc_studio.blank", super.getName(stack));
        }
        return Component.literal(info.songName);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay tooltipDisplay,
                                Consumer<Component> tooltip, TooltipFlag flag) {
        ItemMusicCD.SongInfo info = NetworkDiscs.songInfo(stack);
        if (info == null) {
            tooltip.accept(Component.translatable("tooltip.netmusic_disc_studio.empty")
                    .withStyle(ChatFormatting.RED));
        } else {
            if (info.transName != null && !info.transName.isBlank()) {
                tooltip.accept(Component.translatable("tooltip.netmusic_disc_studio.trans_name", info.transName)
                        .withStyle(ChatFormatting.GOLD));
            }
            if (info.artists != null && !info.artists.isEmpty()) {
                tooltip.accept(Component.translatable("tooltip.netmusic_disc_studio.artists",
                        String.join(" | ", info.artists)).withStyle(ChatFormatting.AQUA));
            }
            tooltip.accept(Component.translatable("tooltip.netmusic_disc_studio.duration",
                    formatTime(info.songTime)).withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        String source = NetworkDiscs.source(stack);
        if (!source.isBlank()) {
            tooltip.accept(Component.translatable("tooltip.netmusic_disc_studio.source", source)
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        tooltip.accept(Component.translatable(usageKey()).withStyle(ChatFormatting.GRAY));
    }

    protected static String formatTime(int seconds) {
        int safe = Math.max(0, seconds);
        return (safe / 60) + ":" + (safe % 60 < 10 ? "0" : "") + (safe % 60);
    }
}
