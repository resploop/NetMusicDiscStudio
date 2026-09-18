package com.netmusic.discstudio.bili;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「切歌之后必须从头播」在<b>服务端</b>的裁决（2026-09-18 第六轮定案）。
 *
 * <h2>为什么前几轮都没修住</h2>
 * 前几轮全都在客户端找"把旧进度发出去的人"（自动恢复、重试、界面拖拽、状态镜像……），
 * 每次都能拦住一两条，却总还剩一条。实测日志（`captured=` 就是本次会话的起播毫秒）给出了铁证：
 *
 * <pre>
 *   23:34:12.830  换曲 |0|2 -&gt; |1|32
 *   23:34:12.835  客户端拦下"自动恢复"：镜像 115‰ × 194s = 22310ms
 *   23:34:15.180  起播追赶完成  captured=22384ms  offset=22.384s   &lt;-- 照样从 22 秒起播
 * </pre>
 *
 * 拦住的那一份<b>不是发出去的那一份</b>。真正的出处在服务端：
 * {@code MP4PlaybackProgressPersistence#targetMillis} —— 全仓<b>只有一处</b>调用它
 * （{@code MP4PlaybackSyncManager#startDiscovered}，紧跟着就 {@code deviceId + "-mp4-" + nanoTime()} 建会话），
 * 而它的取值顺序是：
 *
 * <pre>
 *   fallback = 设备状态.progressPerMille() × 本条目的曲长
 *   runtime  = 运行期进度（<b>只按 deviceId 存、只记 queueIndex</b>）
 *   persisted = mp4_playback.dat 里同 deviceId 同 queueIndex 的 elapsed
 * </pre>
 *
 * <p><b>致命处就是 runtime 那一项</b>：本模组里"一条队列项 = 一整张网络CD"，
 * 所以<b>碟内换曲时 queueIndex 根本不变</b>，{@code runtime.queueIndex() == queueIndex} 恒成立，
 * 于是上一首的播放位置被原样交给下一首当起播位置。日志里
 * {@code 48504ms}（= 上一首在第 16 秒时的位置）和 {@code 22384ms} 都是这么来的。
 *
 * <p>我自己的 {@code Mp4AlbumAdvance} 明明已经用 {@code restartSelected(…, 0L)} 从头起播了，
 * 但直链是<b>异步解析</b>的（实测 1~3 秒，最长见过 6.4 秒）——这期间旧会话已停、
 * 新会话还没建，服务端 40 tick 的发现循环正好从这条缝里抢先按旧进度建了会话，把正确的 0 顶掉。
 *
 * <h2>本类做什么</h2>
 * 判据不看"毫秒数有多接近曲尾"，也不看任何时间窗口是否过期，只看
 * <b>「这个起播位置属不属于这首曲子」</b>：
 *
 * <ul>
 *   <li>{@link #markTrackSwitch(UUID)} —— 推进点（碟内换曲 / 跨碟 / 队列推进前）打一个标记。
 *       只要有标记在，任何"从中间某处起播"的请求一律作废，直到看见新曲目<b>真的从头起播</b>为止
 *       （标记自然关闭，不是靠倒计时）。</li>
 *   <li>{@link #sanitizeOnTarget} —— 服务端自动接管那条路（{@code targetMillis}）。
 *       除了上面的标记，还比对<b>曲目身份</b>（{@code 歌名@曲长}，读物品 NBT 里那条队列项的
 *       {@code netmusic:song_info}）：只要和上次算出起播位置的那一首不是同一首，就说明
 *       这份进度是<b>上一首留下的</b>，直接作废。</li>
 *   <li>{@link #sanitizeOnStart} —— 客户端控制包那条路（{@code MP4PlaybackSyncManager#start}）。
 *       只认标记：玩家自己拖的进度条发生在换曲之后极短时间内才可能被吃掉，
 *       而新曲目一旦从头起播标记就关了，正常拖动不受影响。</li>
 * </ul>
 *
 * <p>两条路各自维护自己的"上一首"记录，互不干扰 —— 免得把客户端送来的歌名与服务端解析出的
 * 歌名之间的细微差异当成"换歌了"。
 */
public final class Mp4SongStartGuard {
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 换曲标记的保险丝：万一新曲目因为别的原因一直没起播，超过这么久就不再强制，
     * 免得把后续正常的"继续播放"也一直按 0 处理。
     */
    private static final long SWITCH_TTL_NANOS = 120_000_000_000L;

    /**
     * 起播位置小于这个值就认为"已经是从头播"，换曲标记到此关闭。
     * 留一点余量是因为服务端会做 {@code gameTime - round(elapsed/50)} 之类的换算。
     */
    private static final long FROM_START_MILLIS = 2_000L;

    /** 上一次由 {@code targetMillis} 算出起播位置的那一首（只在这条入口内部比较）。 */
    private static final Map<UUID, String> LAST_TARGET_SONG = new ConcurrentHashMap<>();

    /** 刚切过歌、还没看到新曲目起播的设备。 */
    private static final Map<UUID, Long> PENDING_SWITCH = new ConcurrentHashMap<>();

    private Mp4SongStartGuard() {
    }

    /**
     * 这台设备当前"该播哪一首"的身份：{@code 歌名@曲长}。
     *
     * <p>取的是物品 NBT 里那条队列项的 {@code netmusic:song_info} —— 服务端解析播放用的就是它，
     * 碟内换曲时它也已经被 {@code NetworkDiscs.selectTrack} 改成新曲目了，所以这是最及时、
     * 最权威的判据（比播放注册表里的歌名早，比客户端镜像可靠）。
     *
     * @return {@code null} 表示这一条不是可播的唱片、或者下标越界（此时不参与判定）
     */
    public static String songKeyOf(ItemStack deviceStack, int queueIndex) {
        if (deviceStack == null || deviceStack.isEmpty() || !(deviceStack.getItem() instanceof MP4Item)) {
            return null;
        }
        List<ItemStack> queue = MP4Item.readQueue(deviceStack);
        if (queueIndex < 0 || queueIndex >= queue.size()) {
            return null;
        }
        ItemMusicCD.SongInfo info = ItemMusicCD.getSongInfo(queue.get(queueIndex));
        if (info == null) {
            return null;
        }
        return info.songName + "@" + info.songTime;
    }

    /** 推进点打标记：这台设备的下一次起播必须从头。 */
    public static void markTrackSwitch(UUID deviceId) {
        if (deviceId == null) {
            return;
        }
        PENDING_SWITCH.put(deviceId, System.nanoTime());
        LOGGER.info("MP4 切歌：设备 {} 的下一首强制从头播放", deviceId);
    }

    /**
     * 服务端自动接管那条路（{@code MP4PlaybackProgressPersistence#targetMillis}）。
     *
     * @param songKey 本次要起播的曲目身份，见 {@link #songKeyOf}
     * @return 应当采用的起播位置；作废时返回 {@code 0}
     */
    public static long sanitizeOnTarget(UUID deviceId, String songKey, long offsetMillis) {
        if (deviceId == null) {
            return offsetMillis;
        }
        String previous = songKey == null ? null : LAST_TARGET_SONG.put(deviceId, songKey);
        boolean switching = pendingSwitch(deviceId);
        if (offsetMillis <= FROM_START_MILLIS) {
            // 新曲目已经从头起播，换曲标记的使命完成 —— 不是靠倒计时，是靠事实。
            if (switching) {
                PENDING_SWITCH.remove(deviceId);
            }
            return offsetMillis;
        }
        boolean songChanged = previous != null && songKey != null && !previous.equals(songKey);
        if (!switching && !songChanged) {
            return offsetMillis;
        }
        LOGGER.info("MP4 起播位置作废（{}）：{}ms -> 从头播放（曲目 {}）",
                songChanged ? "换了一首歌" : "刚切过歌", offsetMillis, songKey);
        return 0L;
    }

    /**
     * 客户端控制包那条路（{@code MP4PlaybackSyncManager#start}）。
     *
     * @return 应当采用的起播位置；作废时返回 {@code 0}
     */
    public static long sanitizeOnStart(UUID deviceId, long offsetMillis) {
        if (deviceId == null) {
            return offsetMillis;
        }
        boolean switching = pendingSwitch(deviceId);
        if (offsetMillis <= FROM_START_MILLIS) {
            if (switching) {
                PENDING_SWITCH.remove(deviceId);
            }
            return offsetMillis;
        }
        if (!switching) {
            return offsetMillis;
        }
        LOGGER.info("MP4 起播位置作废（刚切过歌）：{}ms -> 从头播放", offsetMillis);
        return 0L;
    }

    /** 该设备是否处在"刚切过歌、新曲目还没起播"的窗口里（过期的自动清掉）。 */
    private static boolean pendingSwitch(UUID deviceId) {
        Long at = PENDING_SWITCH.get(deviceId);
        if (at == null) {
            return false;
        }
        if (System.nanoTime() - at > SWITCH_TTL_NANOS) {
            PENDING_SWITCH.remove(deviceId);
            return false;
        }
        return true;
    }

    /** 世界卸载 / 换存档时清空（服务端注册表，键是设备 UUID）。 */
    public static void reset() {
        LAST_TARGET_SONG.clear();
        PENDING_SWITCH.clear();
    }
}
