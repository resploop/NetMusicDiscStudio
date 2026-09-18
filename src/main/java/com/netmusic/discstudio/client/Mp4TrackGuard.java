package com.netmusic.discstudio.client;

import com.mojang.logging.LogUtils;
import com.netmusic.discstudio.client.album.Mp4RealState;
import com.netmusic.discstudio.disc.AlbumPlaylist;
import com.netmusic.discstudio.disc.NetworkDiscs;
import com.zhongbai233.net_music_can_play_bili.client.MP4Client;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.List;
import java.util.UUID;

/**
 * 「这份进度，到底属于哪一首曲子」—— 换曲即作废。
 *
 * <h2>要解决的问题</h2>
 * 上游把"播到哪儿"记成一个<b>千分比</b>，并且到处都用它反算绝对位置：
 *
 * <pre>
 *   MP4FocusScreen           target = mediaProgress × selectedTrackDurationMillis()
 *   MP4Client#save           progressPerMille = round(mediaProgress × 1000)  → 推给服务端
 *   MP4AutoResumeClient      target = round(state.progressPerMille() / 1000 × 曲长)
 *   MP4PlaybackProgress…     target = round(state.progressPerMille() / 1000 × 曲长)
 * </pre>
 *
 * 这些式子的分子（千分比）和分母（曲长）是<b>两次不同的读取</b>，而且这个千分比
 * <b>没有任何"它属于哪一首"的标记</b>。本模组一张碟占一条队列项，"换曲"改的是这条里面的
 * {@code album_playlist.selectedIndex} —— 队列下标都没变，上游完全察觉不到曲子换了。
 * 于是分子还是<b>上一首</b>的，分母已经是<b>这一首</b>的，乘出来是一个没有意义的绝对位置。
 *
 * <h2>2026-09-18 第四次实机取证：真凶是"客户端缓存的那份千分比"</h2>
 * 玩家操作链：
 * <ol>
 *   <li>拖进度条 → {@code MP4FocusScreen#updateSliderValue} → {@code setMediaProgress(比例)}；</li>
 *   <li>松手 → {@code sendPlayback(SEEK, mediaProgress × 曲长)}；</li>
 *   <li>{@code MP4Client#save} → {@code progressPerMille = round(mediaProgress × 1000)}
 *       —— 这份 <b>写进了 {@code MP4Client} 的状态缓存</b>（同时也推给服务端）；</li>
 *   <li>玩家收起界面。此后<b>再没有任何东西刷新这份缓存</b> ——
 *       {@code save()} 只在界面交互时被调用，服务端的状态广播也只在客户端推状态时才回；</li>
 *   <li>这一首播完、服务端换下一首。缓存里的百分比<b>还是玩家拖的那个</b>；</li>
 *   <li>{@code MP4AutoResumeClient} 读的正是 {@code state.progressPerMille()}（<b>不是</b>
 *       {@code MP4FocusState.mediaProgress}）→ {@code target = 那个百分比 × 新曲长}
 *       → 发 {@code START} → <b>新曲目从玩家上次快进到的点开始播</b>。</li>
 * </ol>
 *
 * <p>所以只把 {@code mediaProgress} 归零是<b>不够</b>的（前几版就栽在这里）：
 * 真正被读走的是缓存里那份独立副本。
 *
 * <h2>2026-09-18 第五次取证：为什么"5 秒窗口"仍然拦不住</h2>
 * 之前把窗口做成"换曲后 5 秒内不发包"。但直链异步解析实测要 1~6 秒，重试与解析竞争时更长；
 * 窗口一过期，上面第 6 步照旧发生 —— 日志里 {@code captured} 仍是
 * {@code 146852 / 167954 / 101800…}（都是"上一首的百分比 × 这一首的长度"）。
 *
 * <p>所以判据换成<b>结构性的</b>：换曲之后，一直闭守到<b>新曲目的播放会话真的起来</b>为止：
 * <ol>
 *   <li>观察到"本机播放会话消失"（旧会话被服务端 stop 掉）——记 {@link #noteSessionState}；
 *   <li>随后又观察到"会话出现"——说明起来的是<b>新</b>会话，窗口关闭。</li>
 * </ol>
 * 这样窗口长度天然等于"服务端解析 + 客户端起播"的真实耗时，不再靠拍脑袋。
 * 另有 {@link #MAX_WINDOW_NANOS} 硬上限兜底（万一新曲目的会话一直没起来，
 * 也不能永远把自动恢复挡在外面）。
 *
 * <p>{@link #observe} <b>不是</b>一次性事件：它只更新状态并返回"这一刻变了"，
 * 多个调用方（界面 tick、自动恢复 tick）都能各自看到同一个窗口。
 */
public final class Mp4TrackGuard {
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 硬上限：换曲后最多闭守这么久。
     * <p>兜住"新曲目的会话一直没起来"这种异常，免得自动恢复被无限期挡住。
     */
    private static final long MAX_WINDOW_NANOS = 20_000_000_000L;

    /** 上一次观察到的曲目身份。空串 = 还没观察过（刚进世界）。 */
    private static String lastIdentity = "";

    /** 换曲窗口的起点；0 表示没有窗口。 */
    private static long switchNanos;

    /** 换曲后是否已经看见过"本机没有播放会话"这一瞬（旧会话退场的证据）。 */
    private static boolean sawSessionGap;

    /**
     * 换曲那一刻，客户端镜像里的百分比。
     * <p>{@code >= 0} 表示"这个值属于上一首"。只要它<b>一直没变过</b>，就永远作废 ——
     * 这条判据<b>没有时间上限</b>，用来兜住"窗口关了、但缓存始终没被刷新"的情况：
     * 一个自从换曲就没动过的百分比，不可能是新曲目播出来的。
     */
    private static int staleMirrorPerMille = -1;

    /** 按下进度条那一刻的曲目身份；{@code null} 表示当前没在拖。 */
    private static String scrubIdentity;

    /** 玩家此刻正按着进度条（自己维护，不信任上游可能卡住的 {@code scrubbingProgress}）。 */
    private static boolean dragging;

    /** 这次拖动横跨了换曲，松手时发出的一切控制包都已作废。 */
    private static boolean scrubStale;

    private Mp4TrackGuard() {
    }

    /**
     * 曲目身份：这台设备此刻<b>应该</b>在放哪一首。
     *
     * <p>刻意用物品 NBT 里的"播放意图"（队列下标 + 碟内曲目下标），而不是本机播放注册表里
     * 的歌曲名 —— 后者在换曲空窗里还是旧的那首，等它变过来就已经晚了。
     *
     * @return 形如 {@code <设备ID>|<队列下标>|<碟内曲目下标>}；取不到时用 {@code -} 占位
     */
    public static String identity(ItemStack stack, int queueIndex) {
        UUID deviceId = stack != null && stack.getItem() instanceof MP4Item ? MP4Item.readDeviceId(stack) : null;
        int trackIndex = -1;
        List<ItemStack> queue = stack != null && stack.getItem() instanceof MP4Item ? MP4Item.readQueue(stack)
                : List.of();
        if (queueIndex >= 0 && queueIndex < queue.size()) {
            AlbumPlaylist playlist = NetworkDiscs.playlist(queue.get(queueIndex));
            if (playlist != null && playlist.size() > 1) {
                trackIndex = playlist.selectedIndex();
            }
        }
        return (deviceId == null ? "-" : deviceId.toString()) + "|" + queueIndex + "|" + trackIndex;
    }

    /**
     * 当前这台设备"应该在放哪一首"的签名。
     *
     * <p><b>两个调用方必须共用这一处定位</b>（界面 tick 与自动恢复 tick）。早期版本一边用
     * {@code Mp4RealState.currentStack()}、一边用 {@code MP4Item.findAnyInInventory()}，
     * 背包里有两台 MP4 时算出的身份会在两个值之间来回跳，换曲窗口被反复重开 —— 表现为
     * 拦截时灵时不灵。设备定位与队列下标都取服务端镜像（{@code cachedStateFor}），
     * 保证与自动恢复自己算目标位置时用的是同一份。
     */
    public static String currentIdentity() {
        ItemStack stack = Mp4RealState.currentStack();
        if (!(stack.getItem() instanceof MP4Item)) {
            return "-";
        }
        return identity(stack, MP4Client.cachedStateFor(stack).selectedQueueIndex());
    }

    /** 观察一次"当前这台设备"的曲目身份（两个调用方的统一入口）。 */
    public static boolean observeCurrent() {
        ItemStack stack = Mp4RealState.currentStack();
        if (!(stack.getItem() instanceof MP4Item)) {
            return observe("-");
        }
        int mirrored = MP4Client.cachedStateFor(stack).progressPerMille();
        boolean changed = observe(identity(stack, MP4Client.cachedStateFor(stack).selectedQueueIndex()));
        if (changed) {
            // 换曲这一刻镜像里是几，就把"几"永久标记为上一首的残留。
            staleMirrorPerMille = mirrored;
        } else if (staleMirrorPerMille >= 0 && mirrored != staleMirrorPerMille) {
            // 值终于变了 —— 说明服务端把新曲目的进度推下来了，释放。
            staleMirrorPerMille = -1;
        }
        return changed;
    }

    /**
     * 这个百分比是否已被判定为"上一首的残留"。
     *
     * <p>两条来源：① 还在换曲窗口里；② 它<b>和换曲那一刻一模一样、至今没变过</b> ——
     * 后者没有时间上限，正是为了兜住"窗口因为新会话起来而关闭、但界面关着、
     * 客户端缓存再也没被刷新过"这种情形（{@code save()} 只在界面交互时才会被调用）。
     */
    public static boolean isStaleMirror(int progressPerMille) {
        if (inSwitchWindow()) {
            return true;
        }
        return staleMirrorPerMille >= 0 && progressPerMille == staleMirrorPerMille;
    }

    /**
     * 观察一次曲目身份（可被任意多个调用方反复调用，不消费事件）。
     *
     * @return {@code true} 表示<b>相对上次观察换了曲子</b>。进世界/刚拿到设备时的第一次观察一律
     *         返回 {@code false}（没有"上一首"可言），这样断线重连不会误触发换曲窗口、
     *         把正常的断线续播也拦掉。注意"是否在窗口内"要问 {@link #inSwitchWindow()}，
     *         因为窗口是<b>粘性</b>的，而本方法只有第一次调用会返回 true。
     */
    public static boolean observe(String identity) {
        String safe = identity == null ? "" : identity;
        if (safe.equals(lastIdentity)) {
            return false;
        }
        String previous = lastIdentity;
        lastIdentity = safe;
        if (previous.isEmpty()) {
            return false;
        }
        switchNanos = System.nanoTime();
        sawSessionGap = false;
        // 上一首的"拖完别弹回去"窗口对新曲子无效，立刻结束。
        Mp4SeekHold.release();
        LOGGER.info("MP4 换曲：{} -> {}，换曲期间一律从头起播", previous, safe);
        return true;
    }

    /**
     * 每个客户端刻喂一次"本机这台设备有没有播放会话"。
     *
     * <p>换曲后的窗口就靠它关闭：先看见会话消失（旧会话退场），再看见会话出现（新会话起播）。
     * 之所以要求"先消失再出现"，是因为换曲瞬间<b>旧会话往往还在</b> —— 只认"存在会话"
     * 会让窗口在旧曲目上就被关掉，等于没拦。
     */
    public static void noteSessionState(boolean alive) {
        if (switchNanos == 0L) {
            return;
        }
        if (!alive) {
            sawSessionGap = true;
            return;
        }
        if (sawSessionGap) {
            switchNanos = 0L;
            sawSessionGap = false;
        }
    }

    /** 是否处在"刚换过曲子、新曲目还没起播"的窗口内（窗口内那份镜像进度属于上一首）。 */
    public static boolean inSwitchWindow() {
        if (switchNanos == 0L) {
            return false;
        }
        if (System.nanoTime() - switchNanos >= MAX_WINDOW_NANOS) {
            LOGGER.info("MP4 换曲窗口超过上限，解除闭守（新曲目的播放会话始终没出现）");
            switchNanos = 0L;
            sawSessionGap = false;
            return false;
        }
        return true;
    }

    /**
     * 这份"播到哪儿"是否已经作废（换过曲子、且玩家还没在这一首上亲手设过位置）。
     *
     * <p>调用方拿到 true 时应当把它当 0 用 —— 不是丢弃，是"这首其实在开头"。
     */
    public static boolean mirrorIsVoid() {
        return inSwitchWindow() && !dragging;
    }

    /** 玩家此刻正按着进度条。 */
    public static boolean dragging() {
        return dragging;
    }

    /** 玩家亲手拖了进度条并让它生效（拖动期间没换曲）→ 这一首的进度由他自己定义，作废窗口结束。 */
    public static void markUserSeek() {
        if (switchNanos != 0L) {
            LOGGER.info("MP4 玩家手动设定进度，换曲窗口结束");
        }
        switchNanos = 0L;
        sawSessionGap = false;
        staleMirrorPerMille = -1;
    }

    /** 按下进度条（{@code setScrubbingProgress(true)}）。 */
    public static void beginScrub(String identity) {
        dragging = true;
        if (scrubIdentity != null) {
            return;
        }
        scrubIdentity = identity == null ? "" : identity;
        scrubStale = false;
    }

    /** 松开进度条（{@code setScrubbingProgress(false)}）。 */
    public static void endScrub(String identity) {
        dragging = false;
        if (scrubIdentity == null) {
            return;
        }
        scrubStale = !scrubIdentity.equals(identity == null ? "" : identity);
        scrubIdentity = null;
        if (scrubStale) {
            LOGGER.info("MP4 这一拖横跨了换曲，本次控制包作废");
            return;
        }
        // 这一拖是有效的：进度条停在玩家拖到的位置（Mp4SeekHold），
        // 并且这份进度重新属于当前曲目 —— 关掉换曲窗口，镜像不再被判成 0。
        Mp4SeekHold.arm();
        markUserSeek();
    }

    /**
     * 取走"这次控制包已作废"的标记（取完即清，避免误伤下一个正常操作）。
     */
    public static boolean consumeScrubStale() {
        boolean stale = scrubStale;
        scrubStale = false;
        return stale;
    }

    /**
     * 关界面/取消拖动：清掉拖动相关的簿记。
     * <p>刻意<b>不</b>动 {@link #lastIdentity} 与换曲窗口 —— "上一首 vs 这一首"这件事
     * 跟界面开不开没有关系，清了就会漏掉一次换曲。
     */
    public static void cancelScrub() {
        dragging = false;
        scrubIdentity = null;
        scrubStale = false;
    }

    /** 退出世界 / 重置状态时清空。 */
    public static void reset() {
        lastIdentity = "";
        switchNanos = 0L;
        sawSessionGap = false;
        staleMirrorPerMille = -1;
        dragging = false;
        scrubIdentity = null;
        scrubStale = false;
    }
}
