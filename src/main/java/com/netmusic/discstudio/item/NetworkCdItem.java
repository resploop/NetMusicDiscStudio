package com.netmusic.discstudio.item;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.netmusic.discstudio.client.cover.AlbumCoverTextures;
import com.netmusic.discstudio.disc.AlbumPlaylist;
import com.netmusic.discstudio.disc.NetworkDiscs;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.neoforged.fml.loading.FMLEnvironment;

import java.util.function.Consumer;

/**
 * 「网络CD」。
 * <p>
 * 在唱片刻录机里刻入一整张网易云专辑/歌单，放进现代化唱片机后可以选曲、上一曲/下一曲。
 * <p>
 * 刻录后物品名显示<b>专辑名</b>，tooltip 第一行显示<b>当前曲目</b>；
 * 曲目表保存在物品自己的 {@code album_playlist} 组件里，切换曲目时只改写下标。
 * 客户端的曲目表与封面都从这个组件读，因此唱片机不需要额外的下行协议。
 */
@SuppressWarnings("deprecation")
public class NetworkCdItem extends NetworkDiscItem {
    public NetworkCdItem(Identifier id) {
        super(id);
    }

    @Override
    protected String usageKey() {
        return "tooltip.netmusic_disc_studio.album.usage";
    }

    /** 刻录后用专辑名当物品名；空白时沿用基类的"空盘"文案。 */
    @Override
    public Component getName(ItemStack stack) {
        AlbumPlaylist playlist = NetworkDiscs.playlist(stack);
        if (playlist == null || playlist.isEmpty()) {
            return super.getName(stack);
        }
        return Component.literal(playlist.displayTitle());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay tooltipDisplay,
                                Consumer<Component> tooltip, TooltipFlag flag) {
        AlbumPlaylist playlist = NetworkDiscs.playlist(stack);
        if (playlist != null && !playlist.isEmpty()) {
            ItemMusicCD.SongInfo current = playlist.currentTrack();
            if (current != null) {
                tooltip.accept(Component.translatable("tooltip.netmusic_disc_studio.album.current",
                        playlist.selectedIndex() + 1, playlist.size(),
                        current.songName == null ? "" : current.songName)
                        .withStyle(ChatFormatting.AQUA));
            }
            tooltip.accept(Component.translatable("tooltip.netmusic_disc_studio.album.title",
                    playlist.displayTitle()).withStyle(ChatFormatting.GOLD));
            tooltip.accept(Component.translatable("tooltip.netmusic_disc_studio.album.size",
                    playlist.size()).withStyle(ChatFormatting.GRAY));
            if (playlist.truncated()) {
                tooltip.accept(Component.translatable("tooltip.netmusic_disc_studio.album.truncated",
                        AlbumPlaylist.MAX_TRACKS).withStyle(ChatFormatting.YELLOW));
            }
            // 封面状态只在出问题时提示。兜底贴图与未刻录的唱片长得一模一样，
            // 不说明原因的话玩家只会看到"材质没变"。
            if (FMLEnvironment.getDist().isClient()) {
                AlbumCoverTextures.appendCoverHint(tooltip, playlist.coverUrl());
            }
        }
        super.appendHoverText(stack, context, tooltipDisplay, tooltip, flag);
    }
}
