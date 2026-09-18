package com.netmusic.discstudio.bili;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.netmusic.discstudio.disc.AlbumPlaylist;
import com.netmusic.discstudio.disc.NetworkDiscs;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import com.zhongbai233.net_music_can_play_bili.network.MP4DeviceStateStore;
import com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackControlPacket;
import com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackSyncManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 「一首放完了接下来放什么」在<b>服务端</b>的判定。
 *
 * <p>真相是：MP4 的自动续播<b>不由客户端决定</b>。{@code MP4PlaybackSyncManager} 每刻检查
 * 每个会话，一旦 {@code elapsed >= duration} 就 {@code SESSION_REGISTRY.remove(...)}
 * 然后调 {@code MP4PlaybackQueueController#tryAdvanceQueue}，那里只是
 * {@code MP4PlaybackQueuePolicy.completion(queueIndex, queueSize, repeatMode)} —— 队列下标 ±1，
 * <b>每个条目都当成一首歌</b>。
 *
 * <p>但本模组里一条队列项是<b>一整张网络CD</b>，所以"下一首"应该是这张碟里的下一首。
 * 本类就是在那个决策点上插进去：
 * <ul>
 *   <li><b>碟内还有下一首</b>：改写这张碟保存的曲目下标（顺带同步 {@code netmusic:song_info}），
 *       然后用 {@code restartSelected} 在同一条目上续播。调用方应取消上游逻辑。</li>
 *   <li><b>列表循环（{@code repeatMode == 2}）且这张碟已经放到最后一首</b>：回到本碟第 1 首，
 *       仍然停在同一条目上。列表循环的语义是"<b>这张专辑</b>循环放"，不是"整个队列循环放" ——
 *       少了这一步，听到专辑最后一首会直接跑到队列里<b>另一张碟</b>去。</li>
 *   <li><b>顺序播放播完整张碟</b>：把队列层即将进入的那张碟<b>重置回第 1 首</b>，
 *       再原样交还给上游去做"下一条 / 回到队首 / 停止"。</li>
 * </ul>
 *
 * <p>单曲循环（{@code repeatMode == 1}）一律交还上游：上游的
 * {@code Completion.advance(同一个下标)} 正好就是"重播当前这首"。
 *
 * <p><b>随机播放</b>：上游的 {@code shuffle} 只是个界面开关，自动续播完全不看它
 * （照样 +1）。这里补上，范围与界面上「下一曲」按钮一致：客户端停在某张专辑的曲目表里
 * （由 {@link Mp4AlbumScope} 上报）就在这张碟内随机换一首，否则整个队列随机换一张碟。
 */
public final class Mp4AlbumAdvance {
    private Mp4AlbumAdvance() {
    }

    /** 单曲循环：上游会重播同一条目。 */
    private static final int REPEAT_SINGLE = 1;
    /** 列表循环：播到最后一条回到队首。 */
    private static final int REPEAT_ALL = 2;

    /**
     * 在服务端"一首播完"的决策点上尝试接管。
     *
     * @return {@code true} 表示本模组已经把这张碟切到下一首并续播了，
     *         调用方应当取消上游的队列推进
     */
    public static boolean handleCompletion(ServerLevel level, ItemStack stack) {
        if (level == null || stack == null || stack.isEmpty() || !(stack.getItem() instanceof MP4Item)) {
            return false;
        }
        UUID deviceId = MP4Item.readDeviceId(stack);
        if (deviceId == null) {
            return false;
        }
        MP4DeviceStateStore.DeviceEntry entry = MP4DeviceStateStore.getOrCreate(level, deviceId, stack);
        MP4Item.State state = entry.state();
        if (state.repeatMode() == REPEAT_SINGLE) {
            return false;
        }
        List<ItemStack> queue = new ArrayList<>(MP4Item.readQueue(stack));
        if (queue.isEmpty()) {
            queue = new ArrayList<>(entry.queue());
        }
        int size = queue.size();
        if (size <= 0) {
            return false;
        }
        // 正在播的是哪一条：设备状态里的 selectedQueueIndex 与播放会话是同源的
        // （会话建立、remap、客户端控制包三条路都会同时写这两处）。
        int current = Math.max(0, Math.min(size - 1, state.selectedQueueIndex()));

        if (state.shuffle()) {
            return handleShuffle(level, deviceId, stack, queue, current, size);
        }

        AlbumPlaylist playlist = NetworkDiscs.playlist(queue.get(current));
        if (playlist != null && playlist.size() > 1) {
            int nextTrack = playlist.selectedIndex() + 1;
            if (nextTrack < playlist.size()) {
                return playTrackInAlbum(level, deviceId, stack, queue, current, nextTrack);
            }
            if (state.repeatMode() == REPEAT_ALL) {
                // 列表循环 = 在这张专辑内部首尾相接：最后一首放完回到本碟第 1 首。
                // 不往回走的话，一张专辑听到最后会跳到队列里的<b>另一张碟</b>去 ——
                // 那正是"列表循环不循环、跑去别的专辑"的观感。顺序播放才继续往下走。
                return playTrackInAlbum(level, deviceId, stack, queue, current, 0);
            }
        }
        // 这张碟已经播完（或者本来就是单曲条目）：下面轮到队列层。
        // 队列要进的是另一张碟 —— 那也是一次换歌，同样必须从头起播。
        Mp4SongStartGuard.markTrackSwitch(deviceId);
        resetNextEntry(level, deviceId, stack, queue, current, size, state.repeatMode());
        return false;
    }

    /**
     * 随机播放下的推进。
     * <p>
     * 范围跟视图走，与客户端 {@code Mp4AlbumPlayback#step} 完全一致：
     * <ul>
     *   <li>客户端正停在这张碟的曲目表里 → 这张碟内随机换一首（换到哪首都行，只要求不是当前这首）；</li>
     *   <li>否则 → 整个队列随机换一张碟，并从那里面随机挑一首起播。</li>
     * </ul>
     * 之所以要区分，是因为若在列表层也只在当前专辑内随机，一张碟就会"随机到天荒地老"，
     * 再也走不到队列里的其它碟。
     *
     * @return {@code true} 表示已经接管；{@code false} 表示交还上游（只有一条条目等边缘情形）
     */
    private static boolean handleShuffle(ServerLevel level, UUID deviceId, ItemStack stack,
            List<ItemStack> queue, int current, int size) {
        AlbumPlaylist playing = NetworkDiscs.playlist(queue.get(current));
        if (playing != null && playing.size() > 1 && Mp4AlbumScope.browsing(deviceId, current)) {
            int track = randomIndex(playing.size(), playing.selectedIndex());
            return playTrackInAlbum(level, deviceId, stack, queue, current, track);
        }
        if (size <= 1) {
            // 队列里就这一条，没有"别的碟"可随机：交还上游去决定重播 / 停止。
            return false;
        }
        int entry = randomIndex(size, current);
        ItemStack disc = queue.get(entry);
        AlbumPlaylist picked = NetworkDiscs.playlist(disc);
        if (picked != null && picked.size() > 1) {
            // 换到一张专辑时也从它里面随机挑一首，否则每次进来都只听得到第一首。
            NetworkDiscs.selectTrack(disc, picked.select(randomIndex(picked.size(), -1)));
            Mp4QueueWriter.write(stack, queue);
            MP4DeviceStateStore.updateQueue(level, deviceId, queue);
        }
        return restartOn(level, deviceId, stack, entry);
    }

    /**
     * 专辑内推进：改这张碟的曲目下标并立刻续播。
     *
     * @return {@code false} 表示没能接管（找不到持有人等），此时调用方应让上游照常推进
     */
    private static boolean playTrackInAlbum(ServerLevel level, UUID deviceId, ItemStack stack,
            List<ItemStack> queue, int queueIndex, int trackIndex) {
        ServerPlayer owner = findHolder(level, deviceId);
        if (owner == null) {
            return false;
        }
        ItemStack disc = queue.get(queueIndex);
        AlbumPlaylist playlist = NetworkDiscs.playlist(disc);
        if (playlist == null) {
            return false;
        }
        // 换曲标记：直链是异步解析的（实测 1~6 秒），这期间旧会话已停、新会话还没建，
        // 服务端 40 tick 的发现循环会从缝里按"上一首播到哪儿"抢先建会话，把这里的 0 顶掉。
        // 打了标记之后，那种抢跑的起播位置会被判成"不属于这一首"而作废（见 Mp4SongStartGuard）。
        Mp4SongStartGuard.markTrackSwitch(deviceId);
        // 下标与 song_info 一起改：服务端解析播放用的就是这条的 netmusic:song_info。
        NetworkDiscs.selectTrack(disc, playlist.select(trackIndex));
        Mp4QueueWriter.write(stack, queue);
        MP4DeviceStateStore.updateQueue(level, deviceId, queue);
        // 与客户端"点某首歌"走的是同一条路：先停旧会话，再把这一条按新曲目重新解析起播。
        restartSelected(owner, level, deviceId, stack, queueIndex);
        // 必须在 restartSelected 之后：它内部的 stop() 会先把「旧曲目播到结尾」写进进度。
        resetTrackProgress(level, owner, deviceId, stack, queueIndex);
        return true;
    }

    /**
     * 把播放重启到队列第 {@code queueIndex} 条（同一条目换曲、或跨碟跳转都用它）。
     * <p>
     * 调用前必须已经把改动写进物品 NBT 与设备状态 —— {@code restartSelected} 内部
     * 会重新读一遍队列来解析直链。
     */
    private static boolean restartOn(ServerLevel level, UUID deviceId, ItemStack stack, int queueIndex) {
        ServerPlayer owner = findHolder(level, deviceId);
        if (owner == null) {
            return false;
        }
        // 随机模式换到别的碟/别的曲目：同样是一次换歌，必须从头起播。
        Mp4SongStartGuard.markTrackSwitch(deviceId);
        restartSelected(owner, level, deviceId, stack, queueIndex);
        resetTrackProgress(level, owner, deviceId, stack, queueIndex);
        return true;
    }

    private static void restartSelected(ServerPlayer owner, ServerLevel level, UUID deviceId,
            ItemStack stack, int queueIndex) {
        int volume = MP4DeviceStateStore.getOrCreate(level, deviceId, stack).state().volumePerMille();
        MP4PlaybackControlPacket.restartSelected(owner, stack, deviceId, queueIndex, volume);
    }

    /**
     * 把「这条队列项已经播到第几秒」清零（运行期进度 / 存档 / 设备状态三处）。
     *
     * <p><b>为什么非做不可</b>：专辑内换曲调的 {@code MP4PlaybackControlPacket#restartSelected}
     * 内部第一步是 {@code MP4PlaybackSyncManager#stop}，而 stop 会把<b>旧曲目</b>的播放位置
     * （≈ 整首的长度）连同<b>同一个队列下标</b>写进
     * {@code MP4PlaybackProgressPersistence} 的运行期进度与
     * {@code mp4_playback.dat}，同时把设备状态的 {@code progressPerMille} 留成 ≈ 996‰。
     *
     * <p>接着上游 {@code MP4PlaybackSourceDiscovery} 会在「设备状态说在播、却没有活会话」时
     * 用这份进度（{@code MP4PlaybackProgressPersistence#targetMillis}）把会话恢复回来。
     * 于是新曲目一开场就直接落在<b>整首结尾前几十毫秒</b>：
     * <pre>
     *   targetMillis(duration=300, progress=996‰) = round(0.996 × 300000) = 298800 ms
     * </pre>
     * 几十毫秒后 {@code elapsed >= duration} 成立 → 再次推进 → 又换一首 → 再次落到结尾……
     * 玩家看到的就是<b>随机模式下一首歌播完连续跳好几首</b>，日志里则是一串
     * {@code elapsed=298800ms} / {@code elapsed=228100ms}（都是各自曲长的 99.6%）外加
     * {@code EOFException: no decoded PCM after HTTP seek}（从结尾前开始拉流拉不到 PCM）。
     *
     * <p>清零之后，即使那次异步直链解析被后续操作顶掉、会话没能建起来，
     * 兜底恢复也只会<b>从头</b>重播这首新曲目 —— 一次推进就只换一首。
     */
    public static void resetTrackProgress(ServerLevel level, ServerPlayer owner, UUID deviceId,
            ItemStack stack, int queueIndex) {
        if (level == null || deviceId == null || stack == null || !(stack.getItem() instanceof MP4Item)) {
            return;
        }
        List<ItemStack> queue = new ArrayList<>(MP4Item.readQueue(stack));
        if (queueIndex < 0 || queueIndex >= queue.size()) {
            return;
        }
        ItemMusicCD.SongInfo info = ItemMusicCD.getSongInfo(queue.get(queueIndex));
        int durationSeconds = info != null ? Math.max(0, info.songTime) : 0;
        int volume = MP4DeviceStateStore.getOrCreate(level, deviceId, stack).state().volumePerMille();
        // 运行期进度 + mp4_playback.dat 的 entries：恢复会话时真正被读的那份。
        MP4PlaybackSyncManager.recordProgress(owner, deviceId, queueIndex, 0L, durationSeconds, volume, "", true);
        // 设备状态里的 progressPerMille + devices 分节：算不出队列下标时的兜底基数。
        MP4DeviceStateStore.recordPlayback(level, deviceId, queueIndex, 0L, durationSeconds, volume, "", true);
    }

    /**
     * 队列层要进入的那张碟，重置回第 1 首。
     * <p>
     * 不这么做的话，一张碟播到最后一首后留下的下标会一直留着 —— 下次队列循环回到它时
     * 只会放"最后一首"。注意这里<b>不</b>自己推进队列：算出的下一条与上游
     * {@code MP4PlaybackQueuePolicy.completion} 的规则一致，改完数据就原样交还上游。
     */
    private static void resetNextEntry(ServerLevel level, UUID deviceId, ItemStack stack, List<ItemStack> queue,
            int current, int size, int repeatMode) {
        int nextEntry;
        if (current < size - 1) {
            nextEntry = current + 1;
        } else if (repeatMode == REPEAT_ALL) {
            nextEntry = 0;
        } else {
            // 顺序播到最后一条：上游会停止，没有"下一张"要重置。
            return;
        }
        ItemStack disc = queue.get(nextEntry);
        AlbumPlaylist playlist = NetworkDiscs.playlist(disc);
        if (playlist == null || playlist.size() <= 1 || playlist.selectedIndex() == 0) {
            return;
        }
        NetworkDiscs.selectTrack(disc, playlist.select(0));
        Mp4QueueWriter.write(stack, queue);
        MP4DeviceStateStore.updateQueue(level, deviceId, queue);
    }

    /** 找到手上/背包里拿着这台设备的人（续播需要一个服务端玩家作发起者）。 */
    private static ServerPlayer findHolder(ServerLevel level, UUID deviceId) {
        for (ServerPlayer player : level.players()) {
            ItemStack held = MP4Item.findByDeviceId(player, deviceId);
            if (held.getItem() instanceof MP4Item && deviceId.equals(MP4Item.readDeviceId(held))) {
                return player;
            }
        }
        return null;
    }

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
