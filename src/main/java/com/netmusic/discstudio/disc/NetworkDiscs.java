package com.netmusic.discstudio.disc;

import com.github.tartaricacid.netmusic.init.InitDataComponent;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.netmusic.discstudio.init.ModDataComponents;
import com.netmusic.discstudio.init.ModItems;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/**
 * 两件物品与物品数据之间的读写口。
 * <p>
 * 所有对「可擦写网络唱片」「网络CD」的组件读写都走这里，避免各处散落
 * {@code stack.get(...)} 造成组件与物品不匹配的隐性 bug。
 */
public final class NetworkDiscs {
    private NetworkDiscs() {
    }

    /** 是否是本模组的唱片类物品（空栈返回 false）。 */
    public static boolean isNetworkDisc(ItemStack stack) {
        return !stack.isEmpty()
                && (stack.is(ModItems.ERASABLE_NETWORK_DISC.get()) || stack.is(ModItems.NETWORK_CD.get()));
    }

    /** 是否是可擦写网络唱片（写入单条 B 站视频）。 */
    public static boolean isErasableDisc(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ModItems.ERASABLE_NETWORK_DISC.get());
    }

    /** 是否是网络CD（写入整张专辑/歌单）。 */
    public static boolean isAlbumCd(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ModItems.NETWORK_CD.get());
    }

    /**
     * 读取唱片当前要播放的曲目。
     * <p>
     * 直接复用 NetMusic 自己的 {@code netmusic:song_info} 组件，这样唱片机、
     * 广播喇叭等上游逻辑不需要为本模组新增任何分支。
     *
     * @return 未写入的空白唱片返回 {@code null}
     */
    public static ItemMusicCD.SongInfo songInfo(ItemStack stack) {
        if (!isNetworkDisc(stack)) {
            return null;
        }
        return stack.get(InitDataComponent.SONG_INFO.get());
    }

    /** 读取网络CD的曲目表；不是网络CD或未写入时返回 {@code null}。 */
    public static AlbumPlaylist playlist(ItemStack stack) {
        if (!isAlbumCd(stack)) {
            return null;
        }
        return stack.get(ModDataComponents.ALBUM_PLAYLIST.get());
    }

    /** 是否带有一张非空的曲目表（可顺序播放 / 切歌）。 */
    public static boolean hasPlaylist(ItemStack stack) {
        AlbumPlaylist playlist = playlist(stack);
        return playlist != null && !playlist.isEmpty();
    }

    /** 读取用户写盘时输入的原文，用于界面回显。 */
    public static String source(ItemStack stack) {
        if (!isNetworkDisc(stack)) {
            return "";
        }
        String source = stack.get(ModDataComponents.DISC_SOURCE.get());
        return source == null ? "" : source;
    }

    /**
     * 把一条 B 站选集写进可擦写网络唱片。
     *
     * @param stack  必须是可擦写网络唱片
     * @param info   {@code BiliAudioResolver.resolveBiliSongInfo} 的产物
     * @param source 用户输入的原文
     * @return 写入后的物品（同一实例原地修改并返回）
     */
    public static ItemStack writeBili(ItemStack stack, ItemMusicCD.SongInfo info, String source) {
        if (!isErasableDisc(stack) || info == null) {
            return stack;
        }
        stack.set(InitDataComponent.SONG_INFO.get(), info);
        stack.set(ModDataComponents.DISC_SOURCE.get(), source == null ? "" : source);
        return stack;
    }

    /**
     * 把整张曲目表写进网络CD，并把选中下标归零。
     *
     * @return 写入后的物品；{@code playlist} 为空表时不做任何修改
     */
    public static ItemStack writeAlbum(ItemStack stack, AlbumPlaylist playlist, String source) {
        if (!isAlbumCd(stack) || playlist == null || playlist.isEmpty()) {
            return stack;
        }
        stack.set(ModDataComponents.ALBUM_PLAYLIST.get(), playlist);
        stack.set(ModDataComponents.DISC_SOURCE.get(), source == null ? "" : source);
        applyCurrentTrack(stack, playlist);
        return stack;
    }

    /**
     * 切换到曲目表里的另一首，并同步 {@code netmusic:song_info}。
     *
     * @return 切换后的物品；下标没变化时原样返回
     */
    public static ItemStack selectTrack(ItemStack stack, AlbumPlaylist target) {
        if (!isAlbumCd(stack) || target == null || target.isEmpty()) {
            return stack;
        }
        AlbumPlaylist current = playlist(stack);
        if (current != null && current.selectedIndex() == target.selectedIndex()) {
            return stack;
        }
        stack.set(ModDataComponents.ALBUM_PLAYLIST.get(), target);
        applyCurrentTrack(stack, target);
        return stack;
    }

    /** 把曲目表里当前选中曲目同步到 {@code netmusic:song_info}。 */
    private static void applyCurrentTrack(ItemStack stack, AlbumPlaylist playlist) {
        ItemMusicCD.SongInfo track = playlist.currentTrack();
        if (track != null) {
            stack.set(InitDataComponent.SONG_INFO.get(), track);
        }
    }

    /**
     * 判断两条曲目是否是同一首。
     * <p>
     * 只比 url 与名称：这两个字段足以区分曲目，又不会因为时长、作者列表之类
     * 在上游被重新解析时的细微差异而误判。
     */
    public static boolean sameTrack(ItemMusicCD.SongInfo first, ItemMusicCD.SongInfo second) {
        if (first == null || second == null) {
            return false;
        }
        return Objects.equals(first.songUrl, second.songUrl)
                && Objects.equals(first.songName, second.songName);
    }
}
