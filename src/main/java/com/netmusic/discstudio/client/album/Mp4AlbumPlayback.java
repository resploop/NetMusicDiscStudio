package com.netmusic.discstudio.client.album;

import com.netmusic.discstudio.disc.AlbumPlaylist;
import com.netmusic.discstudio.disc.NetworkDiscs;
import com.netmusic.discstudio.network.Mp4AlbumTrackPacket;
import com.zhongbai233.net_music_can_play_bili.client.MP4Client;
import com.zhongbai233.net_music_can_play_bili.client.MP4FocusState;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlayback;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlaybackRegistry;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSyncPayload;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 专辑条目在 MP4 里的「曲目推进」决策层。
 *
 * <p>一张网络CD在队列里只占一条，它播到第几首记在这条物品自己的
 * {@link AlbumPlaylist#selectedIndex()} 里。所以要换歌只能：
 * <ol>
 *   <li>本地算出「队列下标 + 该碟内的曲目下标」；</li>
 *   <li>把结果发给服务端，由它改写物品 NBT（客户端手上的只是副本，改了没用）。</li>
 * </ol>
 *
 * <p>本类负责第 1 步，并把第 2 步的包发出去。真正"要不要开始播"仍由上游那套控制包驱动，
 * 所以单曲循环 / 顺序 / 列表循环三种模式不需要在这里重复实现。
 *
 * <p><b>随机播放</b>：上游的 {@code shuffle} 只在界面上显示，从不影响选曲，这里补上。
 * 范围按视图走：打开着"正在播的那张专辑"时在专辑内随机，否则在整个队列里随机。
 */
public final class Mp4AlbumPlayback {
    private Mp4AlbumPlayback() {
    }

    /** 列表循环：在<b>这张专辑</b>里首尾相接（不是整个队列）。 */
    private static final int REPEAT_ALL = 2;

    /**
     * 切歌目标。
     *
     * @param queueIndex 队列下标
     * @param trackIndex 该条专辑内要播的曲目下标；{@code -1} 表示不改动条目
     *                   （用这条自己存着的那一首 —— 跨碟切歌时的正确行为）
     */
    public record Target(int queueIndex, int trackIndex) {
    }

    // ───────────────────── 手动 上一曲 / 下一曲 ─────────────────────

    /**
     * @param direction  {@code +1} 下一曲，{@code -1} 上一曲
     * @param shuffle    界面上的随机播放是否开着
     * @param albumOpen  玩家此刻是否停在某张专辑的曲目列表里
     * @param repeatMode 上游的循环模式（{@code 2} = 列表循环）
     * @return {@code null} 表示本模组不参与，交给上游处理
     */
    public static Target step(ItemStack stack, int current, int direction, boolean shuffle, boolean albumOpen,
            int repeatMode) {
        List<ItemStack> queue = MP4Item.readQueue(stack);
        int size = queue.size();
        if (size <= 0) {
            return null;
        }
        int queueIndex = Math.max(0, Math.min(size - 1, current));
        AlbumPlaylist playing = NetworkDiscs.playlist(queue.get(queueIndex));
        boolean insidePlaying = albumOpen && Mp4AlbumView.openedEntryIndex() == queueIndex;

        if (shuffle) {
            // 随机模式下「上一曲 / 下一曲」都当作"换一首别的"，与唱片机一致。
            if (insidePlaying && playing != null && playing.size() > 1) {
                return new Target(queueIndex, randomIndex(playing.size(), playing.selectedIndex()));
            }
            int entry = randomIndex(size, queueIndex);
            AlbumPlaylist picked = NetworkDiscs.playlist(queue.get(entry));
            int track = picked != null && picked.size() > 1 ? randomIndex(picked.size(), -1) : -1;
            return new Target(entry, track);
        }

        if (playing != null) {
            int track = playing.selectedIndex() + direction;
            if (track >= 0 && track < playing.size()) {
                // 还在同一张专辑里：只换这张碟的曲目下标。
                return new Target(queueIndex, track);
            }
            if (repeatMode == REPEAT_ALL && playing.size() > 1) {
                // 列表循环：走到这张专辑的末尾/开头时首尾相接，留在同一张碟里。
                // 少了这一步，专辑里按「下一曲」会直接跨到队列里另一张碟。
                return new Target(queueIndex, direction > 0 ? 0 : playing.size() - 1);
            }
        }
        // 顺序播放走出专辑首尾之外（或者本来就是单曲条目）：跨到相邻一条碟。
        return new Target(Math.max(0, Math.min(size - 1, queueIndex + direction)), -1);
    }

    // ───────────────────── 自动播完的推进 ─────────────────────

    /**
     * 一首歌自动播完时的推进（上游 {@code MP4QueueCompletionPolicy#onCompleted} 的入口）。
     *
     * <p><b>注意</b>：真正决定"放完接着放什么"的是<b>服务端</b>（
     * {@code MP4PlaybackSyncManager} 每刻检查时长，到点就推队列），接管点在
     * {@code Mp4AlbumAdvance#handleCompletion}。这里只是<b>竞态兜底</b>：客户端声音
     * 若抢在服务端那一拍之前走到结尾，就由这一侧把专辑内推进发出去，
     * 免得同一张碟被推进两次（一次专辑内、一次跨碟）。
     *
     * <p>只接管四种情形：随机播放开着、当前这条是多曲目专辑还有下一首、
     * 列表循环下整张专辑放完要回到本碟第 1 首、顺序播放播完要进下一条；
     * 其余一律返回 false 交还上游，让它继续负责单曲循环重播、顺序播放停止、断线恢复等既有行为。
     *
     * @return {@code true} 表示本模组已经接管，调用方应取消上游逻辑
     */
    public static boolean advanceOnCompleted(UUID deviceId, String sessionId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null || deviceId == null
                || !ClientMediaPlayback.isCurrent(deviceId, sessionId)) {
            return false;
        }
        ClientMediaPlaybackRegistry.ActivePlayback active = ClientMediaPlaybackRegistry.get(deviceId);
        if (active == null || active.sourceLocation().sourceType() != ClientMediaSyncPayload.SOURCE_PLAYER) {
            return false;
        }
        if (MP4FocusState.repeatMode() == 1) {
            // 单曲循环：让上游把当前这首原样重播。
            return false;
        }
        ItemStack stack = MP4Item.findByDeviceId(minecraft.player, deviceId);
        if (!(stack.getItem() instanceof MP4Item)) {
            return false;
        }
        List<ItemStack> queue = MP4Item.readQueue(stack);
        int size = queue.size();
        if (size <= 0) {
            return false;   // 空队列：交给上游停止播放
        }
        int current = playingQueueIndex(queue, size, deviceId);

        if (MP4FocusState.shuffle()) {
            // 随机范围跟视图走（与服务端 Mp4AlbumAdvance 同一套规则）：
            // 停在"正在播的那张专辑"的曲目表里就这张碟内随机，否则整个队列随机换一张碟。
            AlbumPlaylist playing = NetworkDiscs.playlist(queue.get(current));
            if (playing != null && playing.size() > 1 && Mp4AlbumView.openedEntryIndex() == current) {
                return advance(deviceId, current, randomIndex(playing.size(), playing.selectedIndex()));
            }
            if (size <= 1) {
                return false;   // 队列里就这一条：没有"别的碟"可随机，交还上游
            }
            int entry = randomIndex(size, current);
            AlbumPlaylist picked = NetworkDiscs.playlist(queue.get(entry));
            int track = picked != null && picked.size() > 1 ? randomIndex(picked.size(), -1) : 0;
            return advance(deviceId, entry, track);
        }

        AlbumPlaylist playing = NetworkDiscs.playlist(queue.get(current));
        if (playing == null || playing.size() <= 1) {
            return false;   // 非专辑条目：队列层的顺序推进交给上游
        }
        int track = playing.selectedIndex() + 1;
        if (track < playing.size()) {
            return advance(deviceId, current, track);
        }
        if (MP4FocusState.repeatMode() == REPEAT_ALL) {
            return advance(deviceId, current, 0);     // 列表循环：整张专辑放完回本碟第 1 首
        }
        int nextEntry = current + 1;
        if (nextEntry < size) {
            return advance(deviceId, nextEntry, 0);   // 下一张碟从头开始
        }
        return false;   // 顺序播放到底：交给上游停止
    }

    /**
     * 正在播的是队列里的哪一条。
     * <p>
     * 界面状态里的那个下标才是跟着播放走的（上游自己的完成策略也用它）；先信它，
     * 只有那条不是专辑时（客户端副本还没同步过来之类）才退回播放注册表里的值。
     */
    private static int playingQueueIndex(List<ItemStack> queue, int size, UUID deviceId) {
        int focus = Math.max(0, Math.min(size - 1, MP4FocusState.selectedQueueIndex()));
        if (NetworkDiscs.playlist(queue.get(focus)) != null) {
            return focus;
        }
        int reported = ClientMediaPlayback.queueIndex(deviceId);
        if (reported >= 0 && reported < size && NetworkDiscs.playlist(queue.get(reported)) != null) {
            return reported;
        }
        return focus;
    }

    /** 改写下标并立刻续播。 */
    private static boolean advance(UUID deviceId, int queueIndex, int trackIndex) {
        restartAt(deviceId, queueIndex, trackIndex);
        MP4Client.updateFocusedLocalState();
        return true;
    }

    // ───────────────────── 发包 ─────────────────────

    /** 点击专辑内某一首：切到该专辑的第 {@code trackIndex} 首，正在播就立刻起播。 */
    public static void playTrack(int queueIndex, int trackIndex) {
        send(currentDeviceId(), queueIndex, trackIndex, MP4FocusState.playing());
    }

    /** 只改写条目里的曲目下标，不碰播放状态（起播交给上游随后的控制包）。 */
    public static void selectTrack(int queueIndex, int trackIndex) {
        send(currentDeviceId(), queueIndex, trackIndex, false);
    }

    /** 自动推进到下一首：改写曲目并直接续播（上游那条路径已经被我们拦住了）。 */
    public static void restartAt(UUID deviceId, int queueIndex, int trackIndex) {
        send(deviceId, queueIndex, trackIndex, true);
    }

    /** 当前 MP4 的设备 ID；拿不到返回 {@code null}。 */
    public static UUID currentDeviceId() {
        ItemStack stack = Mp4RealState.currentStack();
        return stack.getItem() instanceof MP4Item ? MP4Item.readDeviceId(stack) : null;
    }

    private static boolean send(UUID deviceId, int queueIndex, int trackIndex, boolean restart) {
        if (deviceId == null || trackIndex < 0) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getConnection() == null) {
            return false;
        }
        // 与 MP4FocusScreen#sendPlayback 一致：先把界面状态刷进本地缓存，再发控制包。
        MP4Client.cacheFocusedState(deviceId);
        minecraft.getConnection().send(new Mp4AlbumTrackPacket(deviceId, queueIndex, trackIndex, restart));
        // 让界面立刻跟上（不等 20 tick 的兜底轮询）。
        Mp4AlbumView.requestRefresh();
        return true;
    }

    // ───────────────────── 工具 ─────────────────────

    /** 取一个不等于 {@code avoid} 的随机下标（只在真正需要避开时跳一次，不会死循环）。 */
    private static int randomIndex(int size, int avoid) {
        if (size <= 1) {
            return 0;
        }
        int index = ThreadLocalRandom.current().nextInt(size);
        if (index == avoid) {
            index = (index + 1 + ThreadLocalRandom.current().nextInt(size - 1)) % size;
        }
        return index;
    }
}
