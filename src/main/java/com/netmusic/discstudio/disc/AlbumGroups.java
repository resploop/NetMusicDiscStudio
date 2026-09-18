package com.netmusic.discstudio.disc;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 MP4 的播放队列按"来自哪张唱片"切成若干组。
 * <p>
 * MP4 的队列是一条条独立的 {@code ItemStack}，没有分组概念，界面上一行就是一条。
 * 本模组的「网络CD」在入库时会被摊平成 N 条（每条对应专辑里的一首），因此队列里会出现
 * <b>连续若干条形如「同一张专辑的第 k 首」</b> 的条目。
 * <p>
 * 这个类负责把它们重新认出来：<b>连续的、{@link AlbumPlaylist#sourceId()} 相同的条目算一组</b>，
 * 其余条目各自成组。客户端据此把一整张专辑折叠成一行「[专辑] 名称」，点进去再展开曲目。
 * <p>
 * 摊平而不是"一条代表一张专辑"，是为了让 MP4 自己的单曲循环 / 顺序 / 列表循环、
 * 进度推进、断线恢复等全部走上游原有逻辑，一行都不改。
 */
public final class AlbumGroups {
    private AlbumGroups() {
    }

    /** 无标题时的兜底显示名，与上游 {@code MP4FocusState} 的兜底保持一致。 */
    public static final String UNNAMED_DISC = "未命名唱片";

    /**
     * 队列里连续的一段。
     *
     * @param startIndex 该组第一条在队列里的下标
     * @param count      条目数
     * @param albumKey   专辑标识；只有 {@link #isAlbum()} 为真时才有意义
     * @param title      专辑名，或单曲条目的曲目名
     */
    public record Group(int startIndex, int count, String albumKey, String title) {
        /** 是否是一整张专辑（只有超过一条才值得折叠成一行）。 */
        public boolean isAlbum() {
            return count > 1 && albumKey != null && !albumKey.isBlank();
        }

        public int endIndex() {
            return startIndex + count - 1;
        }

        public boolean contains(int index) {
            return index >= startIndex && index <= endIndex();
        }
    }

    /**
     * 取条目所属专辑的标识。
     *
     * @return 不是带曲目表的网络CD、或标识无法确定时返回 {@code null}（该条目按单曲处理）
     */
    public static String albumKey(ItemStack stack) {
        AlbumPlaylist playlist = NetworkDiscs.playlist(stack);
        if (playlist == null || playlist.isEmpty()) {
            return null;
        }
        String sourceId = playlist.sourceId();
        if (sourceId != null && !sourceId.isBlank()) {
            return sourceId;
        }
        // 极少数写入路径可能没存 ID，退回用标题当键；标题也没有就不分组。
        String title = playlist.displayTitle();
        return title == null || title.isBlank() ? null : "title:" + title;
    }

    /** 按连续同名专辑切组；传入的下标顺序就是 MP4 队列顺序。 */
    public static List<Group> group(List<ItemStack> queue) {
        List<Group> groups = new ArrayList<>();
        int cursor = 0;
        while (cursor < queue.size()) {
            ItemStack head = queue.get(cursor);
            String key = albumKey(head);
            if (key == null) {
                groups.add(new Group(cursor, 1, null, entryTitle(head)));
                cursor++;
                continue;
            }
            int end = cursor + 1;
            while (end < queue.size() && key.equals(albumKey(queue.get(end)))) {
                end++;
            }
            groups.add(new Group(cursor, end - cursor, key, albumTitle(head)));
            cursor = end;
        }
        return groups;
    }

    /** 找出某条队列下标落在哪个组里；越界返回 {@code null}。 */
    public static Group groupOf(List<Group> groups, int index) {
        for (Group group : groups) {
            if (group.contains(index)) {
                return group;
            }
        }
        return null;
    }

    /** 条目自身的曲目名（就是它此刻会播放的那一首）。 */
    public static String entryTitle(ItemStack stack) {
        ItemMusicCD.SongInfo info = ItemMusicCD.getSongInfo(stack);
        if (info == null || info.songName == null || info.songName.isBlank()) {
            return UNNAMED_DISC;
        }
        return info.songName;
    }

    /** 专辑名，取不到就退回首条的曲目名。 */
    private static String albumTitle(ItemStack stack) {
        AlbumPlaylist playlist = NetworkDiscs.playlist(stack);
        if (playlist != null) {
            String title = playlist.displayTitle();
            if (title != null && !title.isBlank()) {
                return title;
            }
        }
        return entryTitle(stack);
    }
}
