package com.netmusic.discstudio.client.album;

import com.netmusic.discstudio.disc.AlbumGroups;
import com.zhongbai233.net_music_can_play_bili.client.MP4FocusState;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * MP4 队列在界面上的「折叠视图」。
 * <p>
 * 队列本身是被摊平的（一整张专辑 = 连续的 N 条），这个类负责把它重新折回去：
 *
 * <ul>
 *   <li><b>没进专辑时</b>：一行 = 一组。专辑组显示为 {@code [专辑] 名称 (N首) >}，
 *       单曲条目显示自己的曲目名。</li>
 *   <li><b>进了专辑后</b>：第 0 行是 {@code < 返回 · 名称}，第 1..N 行是专辑里的曲目。</li>
 * </ul>
 *
 * <p>显示的"行空间"与 MP4 真实的队列下标是两套坐标，这里提供
 * {@link #realIndexOf(int)} / {@link #displayIndexOf(int)} 互转。
 * 真实下标只在"选中某一行"的那一刻才写回 {@code MP4FocusState.selectedQueueIndex}，
 * 因此播放、切歌、循环模式仍然完全由上游那套逻辑驱动。
 *
 * <p>队列里一张专辑都没有时 {@link #active()} 返回 false，
 * 此时所有拦截都会让路，MP4 对普通唱片的行为与打本模组之前完全一致。
 */
public final class Mp4AlbumView {
    private Mp4AlbumView() {
    }

    private static List<ItemStack> entries = List.of();
    private static List<AlbumGroups.Group> groups = List.of();
    /** 当前打开的组在 {@link #groups} 里的下标；-1 表示没进专辑。 */
    private static int openedGroup = -1;
    /** 打开的是哪张专辑，队列刷新后据此找回自己的位置。 */
    private static String openedKey = null;
    private static int scroll = 0;
    private static boolean anyAlbum = false;

    // ───────────────────── 队列刷新 ─────────────────────

    /**
     * 重建视图。由 {@code MP4FocusState#loadQueue} 在末尾调用。
     * <p>
     * 过滤条件与上游 {@code loadQueue} 一致（只看有 {@code netmusic:song_info} 的条目、
     * 截断到 {@code QUEUE_SIZE}），这样行空间与真实队列下标才对得上。
     */
    public static void capture(List<ItemStack> rawQueue) {
        List<ItemStack> filtered = new ArrayList<>();
        for (ItemStack stack : rawQueue) {
            if (!MP4Item.isNetMusicDisc(stack)) {
                continue;
            }
            filtered.add(stack.copyWithCount(1));
            if (filtered.size() >= MP4FocusState.QUEUE_SIZE) {
                break;
            }
        }
        entries = List.copyOf(filtered);
        groups = List.copyOf(AlbumGroups.group(entries));
        anyAlbum = false;
        for (AlbumGroups.Group group : groups) {
            if (group.isAlbum()) {
                anyAlbum = true;
                break;
            }
        }
        // 队列变了，按专辑标识找回原来打开的那一组；找不回来就退回列表。
        openedGroup = -1;
        if (anyAlbum && openedKey != null) {
            for (int i = 0; i < groups.size(); i++) {
                AlbumGroups.Group group = groups.get(i);
                if (group.isAlbum() && openedKey.equals(group.albumKey())) {
                    openedGroup = i;
                    break;
                }
            }
        }
        if (openedGroup < 0) {
            openedKey = null;
        }
        clampScroll();
    }

    /** 断线/切世界时清空，避免重连后继承旧设备的队列。 */
    public static void reset() {
        entries = List.of();
        groups = List.of();
        openedGroup = -1;
        openedKey = null;
        scroll = 0;
        anyAlbum = false;
    }

    // ───────────────────── 查询 ─────────────────────

    /** 队列里是否存在"多首"的专辑组；没有就完全放行给上游。 */
    public static boolean active() {
        return anyAlbum;
    }

    public static boolean albumOpen() {
        return openedGroup >= 0 && openedGroup < groups.size() && groups.get(openedGroup).isAlbum();
    }

    /** 当前显示多少行。 */
    public static int displaySize() {
        if (albumOpen()) {
            return 1 + groups.get(openedGroup).count();
        }
        return groups.size();
    }

    /** 真实队列长度（= 被摊平后的条目数）。 */
    public static int realQueueSize() {
        return entries.size();
    }

    public static int scrollOffset() {
        return scroll;
    }

    public static String displayTitle(int row) {
        if (row < 0) {
            return "";
        }
        if (albumOpen()) {
            AlbumGroups.Group group = groups.get(openedGroup);
            if (row == 0) {
                return backLabel(group.title());
            }
            int track = row - 1;
            if (track >= group.count()) {
                return "";
            }
            return (track + 1) + ". " + AlbumGroups.entryTitle(entries.get(group.startIndex() + track));
        }
        if (row >= groups.size()) {
            return "";
        }
        AlbumGroups.Group group = groups.get(row);
        if (group.isAlbum()) {
            return albumTag() + group.title() + " (" + group.count() + "首) >";
        }
        return group.title();
    }

    /** 这一行是不是「返回」行（只在专辑内部存在）。 */
    public static boolean isBackRow(int row) {
        return albumOpen() && row == 0;
    }

    /** 这一行是不是一张专辑（点了会进专辑）。 */
    public static boolean isAlbumRow(int row) {
        return !albumOpen() && row >= 0 && row < groups.size() && groups.get(row).isAlbum();
    }

    /**
     * 显示行 → MP4 真实队列下标。
     *
     * @return {@code -1} 表示这一行没有对应曲目（返回行 / 越界）
     */
    public static int realIndexOf(int row) {
        if (row < 0) {
            return -1;
        }
        if (albumOpen()) {
            AlbumGroups.Group group = groups.get(openedGroup);
            int track = row - 1;
            return track >= 0 && track < group.count() ? group.startIndex() + track : -1;
        }
        return row < groups.size() ? groups.get(row).startIndex() : -1;
    }

    /** MP4 真实队列下标 → 显示行，用于高亮当前播放项。 */
    public static int displayIndexOf(int queueIndex) {
        if (albumOpen()) {
            AlbumGroups.Group group = groups.get(openedGroup);
            return group.contains(queueIndex) ? queueIndex - group.startIndex() + 1 : 0;
        }
        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i).contains(queueIndex)) {
                return i;
            }
        }
        return 0;
    }

    // ───────────────────── 操作 ─────────────────────

    /** 进入第 {@code row} 行对应的专辑。 */
    public static void open(int row) {
        if (row < 0 || row >= groups.size()) {
            return;
        }
        AlbumGroups.Group group = groups.get(row);
        if (!group.isAlbum()) {
            return;
        }
        openedGroup = row;
        openedKey = group.albumKey();
        scroll = 0;
    }

    /** 退回队列列表。 */
    public static void close() {
        openedGroup = -1;
        openedKey = null;
        scroll = 0;
    }

    public static void scrollBy(int delta) {
        scroll += delta;
        clampScroll();
    }

    /** 让某个真实下标所在的行出现在可视区里。 */
    public static void ensureVisible(int queueIndex) {
        if (albumOpen() && !groups.get(openedGroup).contains(queueIndex)) {
            // 播到这张专辑外面去了：不动视图，让玩家自己决定要不要退出去。
            return;
        }
        int row = displayIndexOf(queueIndex);
        int visible = visibleRows();
        if (row < scroll) {
            scroll = row;
        } else if (row >= scroll + visible) {
            scroll = row - visible + 1;
        }
        clampScroll();
    }

    // ───────────────────── 内部 ─────────────────────

    private static void clampScroll() {
        scroll = Math.max(0, Math.min(Math.max(0, displaySize() - visibleRows()), scroll));
    }

    private static int visibleRows() {
        return MP4FocusState.landscape()
                ? MP4FocusState.LANDSCAPE_QUEUE_VISIBLE_ROWS
                : MP4FocusState.PORTRAIT_QUEUE_VISIBLE_ROWS;
    }

    /**
     * 专辑标记，跟着客户端语言走。
     * <p>
     * 上游 MP4 界面的文案是硬编码中文，这里至少做到英文环境下不出现中文。
     */
    private static String albumTag() {
        return isChinese() ? "[专辑] " : "[Album] ";
    }

    private static String backLabel(String title) {
        return isChinese() ? "< 返回 · " + title : "< Back · " + title;
    }

    private static boolean isChinese() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getLanguageManager() == null) {
            return true;
        }
        String language = minecraft.getLanguageManager().getSelected();
        return language == null || language.startsWith("zh");
    }
}
