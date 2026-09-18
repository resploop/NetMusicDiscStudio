package com.netmusic.discstudio.client.album;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.netmusic.discstudio.disc.AlbumPlaylist;
import com.netmusic.discstudio.disc.NetworkDiscs;
import com.netmusic.discstudio.network.Mp4AlbumScopePacket;
import com.zhongbai233.net_music_can_play_bili.client.MP4FocusState;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * MP4 队列在界面上的「专辑视图」。
 *
 * <p><b>队列模型</b>：一张「网络CD」（整张专辑）在 MP4 队列里<b>只占一条</b>，
 * 与普通单曲唱片完全一样。所以 18 条的队列上限就是「能放 18 张碟」，
 * 不存在"一张专辑吃掉整个队列"的问题。
 *
 * <p>专辑内部的曲目切换不走队列，而是改写这条队列项自己保存的
 * {@link AlbumPlaylist#selectedIndex()} —— 也就是服务端那张碟"当前播到第几首"。
 * 改写由 {@link Mp4AlbumPlayback} 发一个 {@code mp4_album_track} 包交给服务端完成。
 *
 * <p><b>显示层</b>：这里只负责把它画成好用的两层面板：
 * <ul>
 *   <li><b>列表层</b>：一行 = 一条队列项。专辑显示为 {@code [专辑] 名称 (N首) >}，
 *       单曲显示曲目名。</li>
 *   <li><b>专辑内</b>：第 0 行是 {@code < 返回 · 名称}，其后是 {@code 1. 曲目名} …。</li>
 * </ul>
 *
 * <p>显示行与队列下标是两套坐标，由 {@link #realIndexOf(int)} /
 * {@link #displayIndexOf(int)} 互相换算。真实下标只在渲染器与点击分发处使用，
 * 队列本身、播放推进、循环模式仍然完全由上游那套逻辑驱动。
 *
 * <p><b>打开的是哪张专辑会被保留</b>：收起 MP4 再拿出来还停在同一张专辑的曲目表里
 * （靠 {@link #openedKey} 在队列刷新后找回；玩家自己按「返回」才回列表层）。
 * 这不只是观感问题 —— 「随机播放」的范围是跟着视图走的，
 * 视图被重置成全队列，按下一曲就会跳到别的专辑去。
 *
 * <p>队列里一张专辑都没有时 {@link #active()} 返回 false，所有拦截都会让路，
 * MP4 的行为与打本模组之前完全一致。
 */
public final class Mp4AlbumView {
    private Mp4AlbumView() {
    }

    private static final int REFRESH_DELAY_TICKS = 3;
    private static final int POLL_INTERVAL_TICKS = 20;

    /** 队列层（没有打开任何专辑）。 */
    private static final int LIST_LAYER = -1;
    /** 还没向服务端上报过视图层。 */
    private static final int SCOPE_UNKNOWN = Integer.MIN_VALUE;

    /** 队列快照；与 {@code MP4FocusState.queueTitles} 同一份数据源。 */
    private static List<ItemStack> entries = List.of();
    /** 与 {@link #entries} 等长；不是「多首专辑」的条目为 {@code null}。
     *  <b>允许 null</b>，所以这份列表不能用 {@code List.copyOf} 构造（它对 null 元素抛 NPE，
     *  往队列里放一张单曲唱片就会把客户端打崩）。 */
    private static List<AlbumPlaylist> albums = List.of();
    /** 当前打开的专辑在 {@link #entries} 里的下标；-1 表示停在列表层。 */
    private static int openedEntry = -1;
    /** 打开的是哪张专辑，队列刷新后据此找回自己的位置。 */
    private static String openedKey;
    private static int scroll;
    private static boolean hasAlbum;
    private static int refreshDelay;
    private static int pollTicks;
    /** 最后一次成功上报给服务端的视图层，变了才发包。 */
    private static int reportedScope = SCOPE_UNKNOWN;

    // ───────────────────── 队列快照 ─────────────────────

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
        List<AlbumPlaylist> parsed = new ArrayList<>(filtered.size());
        boolean anyAlbum = false;
        for (ItemStack stack : filtered) {
            AlbumPlaylist playlist = NetworkDiscs.playlist(stack);
            if (playlist != null && playlist.size() > 1) {
                anyAlbum = true;
            } else {
                playlist = null;
            }
            parsed.add(playlist);
        }
        entries = List.copyOf(filtered);
        // 注意：parsed 里"不是专辑"的位置是 null，List.copyOf 会直接抛 NPE
        // （往 MP4 队列里放一张普通单曲唱片就会触发，表现为进界面瞬间客户端崩溃）。
        albums = Collections.unmodifiableList(new ArrayList<>(parsed));
        hasAlbum = anyAlbum;
        // 队列变了，按专辑标识找回原来打开的那一张；找不回来就退回列表。
        openedEntry = -1;
        if (anyAlbum && openedKey != null) {
            for (int i = 0; i < albums.size(); i++) {
                AlbumPlaylist playlist = albums.get(i);
                if (playlist != null && openedKey.equals(albumKey(playlist))) {
                    openedEntry = i;
                    break;
                }
            }
        }
        // 只有"确实拿到了一份非空队列、里面却没有那张专辑"才算它真的被拿走了。
        // 快照偶尔会是空的（设备状态还没同步过来、容器同步的中间态），
        // 那只是暂时看不到队列，不该把玩家进过哪张专辑也一起忘掉。
        if (openedEntry < 0 && !filtered.isEmpty()) {
            openedKey = null;
        }
        clampScroll();
        refreshDelay = 0;
        syncScope();
    }

    /** 断线/切世界时清空，避免重连后继承旧设备的队列。 */
    public static void reset() {
        entries = List.of();
        albums = List.of();
        openedEntry = -1;
        openedKey = null;
        scroll = 0;
        hasAlbum = false;
        refreshDelay = 0;
        pollTicks = 0;
        // 连的是哪个世界都变了，之前报过的视图层不再可信；重进界面时重新报一次。
        reportedScope = SCOPE_UNKNOWN;
    }

    /**
     * 跟随物品 NBT 刷新队列快照。
     * <p>
     * 专辑切曲是服务端改写「那张碟当前播到第几首」，客户端看到的只是容器同步过来的副本，
     * 不会触发 {@code loadQueue}。所以这里在 MP4 界面打开期间：
     * <ul>
     *   <li>自己发过切曲请求后，{@link #requestRefresh()} 安排一次近距刷新；</li>
     *   <li>另外每 {@value #POLL_INTERVAL_TICKS} tick 兜底比对一次，兼顾别人操作同一台设备的情况。</li>
     * </ul>
     */
    public static void tickSync() {
        syncScope();
        if (!MP4FocusState.active()) {
            // 手持界面没打开时不需要跟随：快照会在打开界面的那一刻重建。
            return;
        }
        if (refreshDelay > 0) {
            refreshDelay--;
            if (refreshDelay == 0) {
                refresh();
            }
            return;
        }
        if (!hasAlbum) {
            pollTicks = 0;
            return;
        }
        if (++pollTicks < POLL_INTERVAL_TICKS) {
            return;
        }
        pollTicks = 0;
        refresh();
    }

    /** 安排一次近距刷新（自己刚改写过队列时用）。 */
    public static void requestRefresh() {
        refreshDelay = REFRESH_DELAY_TICKS;
        // 界面刚打开 / 刚结束一次切歌：重新确认一次视图层（可能换了另一台 MP4）。
        reportedScope = SCOPE_UNKNOWN;
    }

    private static void refresh() {
        ItemStack stack = Mp4RealState.currentStack();
        if (!(stack.getItem() instanceof MP4Item)) {
            return;
        }
        List<ItemStack> queue = MP4Item.readQueue(stack);
        if (sameAsSnapshot(queue)) {
            return;
        }
        // loadQueue 内部会回调 capture 重建本视图。
        MP4FocusState.loadQueue(queue);
    }

    private static boolean sameAsSnapshot(List<ItemStack> queue) {
        // 用与 capture 完全相同的过滤条件逐项比对，而不是拿原始队列的长度去比 ——
        // 队列里混进普通唱片时原始长度永远对不上，会白刷一轮（每秒重建一次快照）。
        int i = 0;
        for (ItemStack stack : queue) {
            if (!MP4Item.isNetMusicDisc(stack)) {
                continue;
            }
            if (i >= entries.size()) {
                return false;
            }
            if (!ItemStack.isSameItem(stack, entries.get(i))) {
                return false;
            }
            AlbumPlaylist fresh = NetworkDiscs.playlist(stack);
            AlbumPlaylist snapshot = albums.get(i);
            if ((fresh == null) != (snapshot == null)) {
                return false;
            }
            if (fresh != null
                    && (fresh.selectedIndex() != snapshot.selectedIndex() || fresh.size() != snapshot.size())) {
                return false;
            }
            if (++i >= MP4FocusState.QUEUE_SIZE) {
                break;
            }
        }
        return i == entries.size();
    }

    /**
     * 把「此刻停在哪个视图层」告诉服务端：打开着哪张专辑，或者停在队列列表层。
     * <p>
     * 服务端要用它决定<b>自动播完时「随机播放」的取值范围</b>（见
     * {@code Mp4AlbumAdvance}）：停在某张专辑的曲目表里就在这张碟内随机换一首，
     * 停在列表层就在整个队列里随机换一张碟 —— 与界面上「下一曲」按钮的规则完全一致
     * （见 {@link Mp4AlbumPlayback#step}）。上游的 {@code shuffle} 只管显示、
     * 从来不影响选曲，所以这份信息只能由界面这一侧补给它。
     * <p>
     * 只在取值真的变了才发包：视图层的变更点很少（进专辑 / 返回 / 队列刷新找回 / 断线清空），
     * 不会变成每刻都在跑的网络流量。
     */
    private static void syncScope() {
        int scope = openedAlbum() != null ? openedEntry : LIST_LAYER;
        if (scope == reportedScope) {
            return;
        }
        // 手里没拿着这台 MP4（或者还没进世界）时发不出去，等下一次变化再试。
        UUID deviceId = Mp4AlbumPlayback.currentDeviceId();
        if (deviceId == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getConnection() == null) {
            return;
        }
        minecraft.getConnection().send(new Mp4AlbumScopePacket(deviceId, scope));
        reportedScope = scope;
    }

    // ───────────────────── 查询 ─────────────────────

    /** 队列里是否存在「多首」的专辑；没有就完全放行给上游。 */
    public static boolean active() {
        return hasAlbum;
    }

    public static boolean albumOpen() {
        return openedAlbum() != null;
    }

    /** 当前显示多少行。 */
    public static int displaySize() {
        AlbumPlaylist opened = openedAlbum();
        return opened != null ? 1 + opened.size() : entries.size();
    }

    public static int realQueueSize() {
        return entries.size();
    }

    public static int scrollOffset() {
        return scroll;
    }

    /** 列表层的某一行对应的队列下标；专辑内返回打开的那张专辑的下标。 */
    public static int entryIndexOfDisplayRow(int row) {
        AlbumPlaylist opened = openedAlbum();
        if (opened != null) {
            return row == 0 ? -1 : openedEntry;
        }
        return row >= 0 && row < entries.size() ? row : -1;
    }

    /** 当前打开的专辑在队列里的下标；没打开返回 -1。 */
    public static int openedEntryIndex() {
        return openedAlbum() != null ? openedEntry : -1;
    }

    /** 某条队列项带的曲目表；不是专辑返回 {@code null}。 */
    public static AlbumPlaylist playlistAt(int queueIndex) {
        return queueIndex >= 0 && queueIndex < albums.size() ? albums.get(queueIndex) : null;
    }

    /** 某条队列项「当前播到第几首」；不是专辑返回 -1。 */
    public static int currentTrackOf(int queueIndex) {
        AlbumPlaylist playlist = playlistAt(queueIndex);
        return playlist == null ? -1 : playlist.selectedIndex();
    }

    public static String displayTitle(int row) {
        if (row < 0) {
            return "";
        }
        AlbumPlaylist opened = openedAlbum();
        if (opened != null) {
            if (row == 0) {
                return backLabel(opened.displayTitle());
            }
            int track = row - 1;
            if (track >= opened.size()) {
                return "";
            }
            return (track + 1) + ". " + trackTitle(opened.tracks().get(track));
        }
        if (row >= entries.size()) {
            return "";
        }
        AlbumPlaylist playlist = albums.get(row);
        if (playlist != null) {
            return albumTag() + playlist.displayTitle() + " (" + playlist.size() + "首) >";
        }
        return trackTitle(ItemMusicCD.getSongInfo(entries.get(row)));
    }

    /** 这一行是不是「返回」行（只在专辑内部存在）。 */
    public static boolean isBackRow(int row) {
        return albumOpen() && row == 0;
    }

    /** 这一行是不是一张专辑（点了会进专辑）。 */
    public static boolean isAlbumRow(int row) {
        return !albumOpen() && row >= 0 && row < albums.size() && albums.get(row) != null;
    }

    /**
     * 显示行 → MP4 真实队列下标。
     * <p>
     * 专辑内的曲目行全部映射到同一个队列下标（就是那张碟），
     * 具体第几首由 {@link #trackIndexOf(int)} 给出。
     *
     * @return {@code -1} 表示这一行没有对应队列项（返回行 / 越界）
     */
    public static int realIndexOf(int row) {
        if (row < 0 || row >= displaySize()) {
            return -1;
        }
        return entryIndexOfDisplayRow(row);
    }

    /** 显示行 → 该专辑内的曲目下标；不是曲目行返回 -1。 */
    public static int trackIndexOf(int row) {
        AlbumPlaylist opened = openedAlbum();
        if (opened == null || row < 1) {
            return -1;
        }
        int track = row - 1;
        return track < opened.size() ? track : -1;
    }

    /**
     * MP4 真实队列下标 → 显示行，用于高亮当前播放项。
     * <p>
     * 专辑打开且高亮的正是这张专辑时，高亮落在它当前播的那一首上；
     * 打开的是别的专辑（当前播的那条不在视野里）时返回 {@code -1}，即不高亮任何一行，
     * 免得把"返回"那一行点亮造成误会。
     */
    public static int displayIndexOf(int queueIndex) {
        AlbumPlaylist opened = openedAlbum();
        if (opened != null) {
            if (queueIndex != openedEntry) {
                return -1;
            }
            int track = Math.max(0, Math.min(opened.size() - 1, opened.selectedIndex()));
            return 1 + track;
        }
        if (entries.isEmpty()) {
            return 0;
        }
        return Math.max(0, Math.min(entries.size() - 1, queueIndex));
    }

    // ───────────────────── 操作 ─────────────────────

    /** 进入第 {@code row} 行对应的专辑。 */
    public static void open(int row) {
        AlbumPlaylist playlist = row >= 0 && row < albums.size() ? albums.get(row) : null;
        if (playlist == null) {
            return;
        }
        openedEntry = row;
        openedKey = albumKey(playlist);
        scroll = 0;
        syncScope();
    }

    /** 退回队列列表。 */
    public static void close() {
        openedEntry = -1;
        openedKey = null;
        scroll = 0;
        syncScope();
    }

    public static void scrollBy(int delta) {
        scroll += delta;
        clampScroll();
    }

    /**
     * 让某个<b>显示行</b>出现在可视区里。
     * <p>
     * 点击列表时传被点的那一行：它本来就在视野里，于是滚动位置原封不动。
     * 之前这里按"当前播放曲目"的行号去算，点列表靠下的行会把整段列表推走，
     * 玩家看到的现象就是"点一下列表，列表自己往上/往下跳"。
     */
    public static void ensureRowVisible(int row) {
        if (row < 0) {
            return;
        }
        int visible = visibleRows();
        if (row < scroll) {
            scroll = row;
        } else if (row >= scroll + visible) {
            scroll = row - visible + 1;
        }
        clampScroll();
    }

    /** 让某条队列项所在的行出现在可视区里（跨碟切歌时跟随播放项）。 */
    public static void ensureVisible(int queueIndex) {
        AlbumPlaylist opened = openedAlbum();
        if (opened != null && queueIndex != openedEntry) {
            // 播到这张专辑外面去了：不动视图，让玩家自己决定要不要退出去。
            return;
        }
        ensureRowVisible(displayIndexOf(queueIndex));
    }

    // ───────────────────── 内部 ─────────────────────

    private static AlbumPlaylist openedAlbum() {
        return openedEntry >= 0 && openedEntry < albums.size() ? albums.get(openedEntry) : null;
    }

    private static void clampScroll() {
        scroll = Math.max(0, Math.min(Math.max(0, displaySize() - visibleRows()), scroll));
    }

    private static int visibleRows() {
        return MP4FocusState.landscape()
                ? MP4FocusState.LANDSCAPE_QUEUE_VISIBLE_ROWS
                : MP4FocusState.PORTRAIT_QUEUE_VISIBLE_ROWS;
    }

    static String albumKey(AlbumPlaylist playlist) {
        String sourceId = playlist.sourceId();
        return sourceId == null || sourceId.isBlank() ? "title:" + playlist.displayTitle() : sourceId;
    }

    private static String trackTitle(ItemMusicCD.SongInfo info) {
        if (info == null) {
            return "无曲目";
        }
        String name = info.songName;
        return name == null || name.isBlank() ? "NetMusic 唱片" : name;
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
