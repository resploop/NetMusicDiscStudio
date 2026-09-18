package com.netmusic.discstudio.bili;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * 把「起播位置落在曲尾」的请求改成从头播放。
 *
 * <p><b>为什么需要这条底线。</b>MP4 一条会话的起播位置不是"这次要放哪首"决定的，
 * 而是从一堆"上次播到哪"的进度里算出来的：
 * <ul>
 *   <li>{@code MP4PlaybackProgressPersistence} 的运行期进度与 {@code mp4_playback.dat}；</li>
 *   <li>设备状态的 {@code progressPerMille}（会被同步到客户端，客户端
 *       {@code MP4AutoResumeClient} 又拿它算一个 START 的目标位置）；</li>
 *   <li>客户端本地时间线的 elapsed（流失败重试时 {@code Mp4ClientMediaRetryPolicy}
 *       会把它原样发给服务端）。</li>
 * </ul>
 *
 * <p>而"一首放完了"这件事本身就会把这些进度写成<b>接近整首长度</b>的值
 * （会话的 elapsed 被 {@code clampElapsed} 夹在 {@code 曲长}，完成那一刻正好顶格）。
 * 于是在<b>专辑内换曲</b>这种"同一条队列下标、换掉里面的曲子"的场景里：
 *
 * <pre>
 *   旧曲目播到结尾 → 进度写成"≈ 整首"  → 换曲后新会话按这份进度起播
 *   → 新曲目《一开场就在自己末尾前几十毫秒》
 *   → elapsed ≥ duration 立刻成立 → 服务端判定放完 → 推进下一首
 *   → 又写一份"≈ 整首" → 永远停不下来
 * </pre>
 *
 * <p>玩家看到的就是：一首歌刚跳到下一首，进度条直接停在最后，紧接着又跳下一首。
 *
 * <h2>两类窗口，别混用</h2>
 * <ul>
 *   <li>{@link #shouldRestartFromBeginning} —— 只认"最后 3 秒"，用在服务端
 *       {@code clampElapsed} 那个总兜底口上。窗口必须小：那里的入参也可能是玩家
 *       自己拖进度条的结果，窗口开大了会把"拖到 90% 听一下"也变成从头播。</li>
 *   <li>{@link #isRetiredProgress} —— 窗口宽得多（曲长的 1/10，至少 5 秒），
 *       只用在客户端 {@code MP4AutoResumeClient} 这条"自动恢复播放"的路上。
 *       那条路上的目标位置<b>只会</b>来自"上次播到哪"的千分比，玩家碰不到它，
 *       所以可以放心地判定"千分比这么高 = 那是上一首留下的、早就作废的进度"。</li>
 * </ul>
 *
 * <h2>2026-09-18 实机取证实录</h2>
 * 守护日志里三条被拦下的起播位置，恰好都是整数倍关系，直接坐实了
 * "偏移 = 千分比 × 曲长"：
 * <pre>
 *   110445ms / 111s = 995‰      104265ms / 105s = 993‰      110768ms / 112s = 989‰
 * </pre>
 * 而 989~995‰ 正是<b>上一首</b>播完时的进度。拦不到的（比如 877‰ × 130s）就直接按
 * 接近结尾起播，于是 5~20 秒后就"播完"，再推进、再落到新的曲尾 —— 连环跳就是这么来的。
 */
public final class Mp4StartOffsetGuard {
    /**
     * 距曲尾这么近，就认为"这首已经放完了"（服务端总兜底口用）。
     *
     * <p>2026-09-18 二次取证后从 3 秒放宽到 5 秒：出问题的起播位置并不总落在最后 1 秒内。
     * 日志里 {@code captured=156375ms} 打在 185 秒的曲子上、{@code 110702ms} 打在 111 秒的
     * 曲子上，离曲尾还有 3~5 秒 —— 3 秒的窗口正好把它们漏掉。反过来，玩家真会主动拖到
     * "最后 5 秒"去听一下的场景基本不存在。
     */
    private static final long TAIL_WINDOW_MILLIS = 5000L;

    /** 客户端"自动恢复播放"那条路上的窗口下限。 */
    private static final long RETIRED_MIN_WINDOW_MILLIS = 5000L;

    /** 客户端"自动恢复播放"那条路上，窗口按曲长的这个比例算。 */
    private static final long RETIRED_WINDOW_DIVISOR = 10L;

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 上一条"自动恢复被拦下"日志的指纹，用来去重（那条路每客户端刻都跑）。 */
    private static long lastAutoResumeLogKey = Long.MIN_VALUE;

    private Mp4StartOffsetGuard() {
    }

    /**
     * @param elapsedMillis   这次想要起播的位置
     * @param durationSeconds 这首曲子的长度（秒，来自 {@code netmusic:song_info} 的
     *                        {@code time_second}）
     * @return {@code true} 表示这个位置落在曲子最后 5 秒内，调用方应改成从 0 起播
     */
    public static boolean shouldRestartFromBeginning(long elapsedMillis, int durationSeconds) {
        if (durationSeconds <= 0 || elapsedMillis <= 0L) {
            return false;
        }
        long durationMillis = durationSeconds * 1000L;
        // 短曲子按比例收窄窗口，免得只有几秒的片段也被判成"已播完"。
        long window = Math.min(TAIL_WINDOW_MILLIS, durationMillis / 3L);
        return elapsedMillis >= durationMillis - window;
    }

    /**
     * 判定并给出真正该用的起播位置（落在曲尾就返回 0，否则原样返回）。
     */
    public static long sanitize(long elapsedMillis, int durationSeconds) {
        if (!shouldRestartFromBeginning(elapsedMillis, durationSeconds)) {
            return elapsedMillis;
        }
        LOGGER.info("MP4 起播位置落在曲尾（{}ms / {}s），改为从头播放", elapsedMillis, durationSeconds);
        return 0L;
    }

    /**
     * 「自动恢复播放」这条路上的窗口：曲长的 1/10，至少 5 秒，再宽也不超过曲长的 1/3。
     *
     * @return 窗口毫秒数；{@code durationSeconds <= 0} 时返回 0
     */
    public static long retiredWindowMillis(int durationSeconds) {
        if (durationSeconds <= 0) {
            return 0L;
        }
        long durationMillis = durationSeconds * 1000L;
        long window = Math.max(RETIRED_MIN_WINDOW_MILLIS, durationMillis / RETIRED_WINDOW_DIVISOR);
        return Math.min(window, Math.max(1L, durationMillis / 3L));
    }

    /**
     * 这个位置是不是"上一首留下的、已经作废的进度"。
     *
     * <p>只给客户端 {@code MP4AutoResumeClient} 用：那里的位置 = {@code 千分比 × 曲长}，
     * 千分比一旦这么高，说明它记的是<b>上一首播完时</b>的进度，而曲子早就换成新的了。
     * 拿它去恢复播放，只会把新曲子一开场就送到结尾。
     *
     * @param positionMillis  由千分比算出来的目标位置
     * @param durationSeconds 当前这首（新的那一首）的长度
     */
    public static boolean isRetiredProgress(long positionMillis, int durationSeconds) {
        if (durationSeconds <= 0 || positionMillis <= 0L) {
            return false;
        }
        long durationMillis = durationSeconds * 1000L;
        return positionMillis >= durationMillis - retiredWindowMillis(durationSeconds);
    }

    /**
     * 打出「自动恢复播放被拦下」的说明，方便实机日志核对（同一条目标只打一次）。
     *
     * @param positionMillis  由千分比算出来的目标位置
     * @param progressPerMille 客户端镜像里那个千分比
     * @param retired         是不是"千分比高得像曲尾"
     * @param switching       是不是"刚换过曲子，这份进度属于上一首"
     */
    public static void logAutoResumeBlocked(long positionMillis, int durationSeconds, int progressPerMille,
            boolean retired, boolean switching) {
        long key = positionMillis * 31L + durationSeconds;
        if (key == lastAutoResumeLogKey) {
            return;
        }
        lastAutoResumeLogKey = key;
        LOGGER.info("MP4 自动恢复被拦下（{}）：镜像 {}‰ × {}s = {}ms，起播位置交给服务端权威决定",
                switching ? "刚换过曲子，这份进度属于上一首" : (retired ? "镜像进度落在曲尾" : "镜像进度不可信"),
                progressPerMille, durationSeconds, positionMillis);
    }
}
