package com.netmusic.discstudio.client;

/**
 * 拖完进度条之后的一小段「别把进度条拉回去」的窗口。
 *
 * <p><b>问题</b>：松开进度条时上游只做了两件事 —— 结束拖动、把 SEEK 发给服务端。
 * 服务端要重新解析直链、重开音频流，再把新的播放状态广播回来，这一圈少则几百毫秒、
 * 多则一两秒。而界面每帧都会用<b>当前本机正在播的那条媒体</b>把
 * {@code MP4FocusState.mediaProgress} 覆盖回去（{@code MP4PlaybackUiState#syncFocusedProgress}），
 * 于是玩家看到的是：拖完松手，进度条"啪"地弹回拖动前的位置 —— 看起来像拖动失败，
 * 甚至像"拖了但没声音"。
 *
 * <p><b>做法</b>：松手后（{@code MP4FocusState#setScrubbingProgress(false)}）开一个短窗口，
 * 窗口内 {@code syncFocusedProgress} 被跳过，玩家拖到哪就停在哪；
 * 等新的播放状态到位后窗口过期，进度条自然继续跟着真实播放走。
 *
 * <p>只影响进度条的显示，不碰任何播放状态或控制包。
 */
public final class Mp4SeekHold {
    private Mp4SeekHold() {
    }

    /**
     * 窗口长度。
     * <p>
     * 取 2.5 秒：足够覆盖「服务端解析直链 + 客户端准备音频」的常规耗时，
     * 又短到万一 SEEK 没成功也能很快恢复跟随真实播放，不会一直骗玩家。
     */
    private static final long HOLD_NANOS = 2_500_000_000L;

    private static long holdUntilNanos;

    /** 松开进度条后调用：进入保温窗口。 */
    public static void arm() {
        holdUntilNanos = System.nanoTime() + HOLD_NANOS;
    }

    /** 当前是否处于保温窗口内。 */
    public static boolean holding() {
        return System.nanoTime() < holdUntilNanos;
    }

    /** 立即结束窗口（切歌、关界面等场景）。 */
    public static void release() {
        holdUntilNanos = 0L;
    }
}
