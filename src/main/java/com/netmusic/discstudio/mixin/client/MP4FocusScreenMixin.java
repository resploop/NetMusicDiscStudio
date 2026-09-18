package com.netmusic.discstudio.mixin.client;

import com.netmusic.discstudio.client.Mp4TrackGuard;
import com.zhongbai233.net_music_can_play_bili.client.MP4FocusState;
import com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackControlPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 界面这一侧的两件事：丢掉过期的控制包、关界面时收拾干净。
 *
 * <h2>1. 丢掉"横跨了换曲"的那一次控制包</h2>
 * {@code MP4FocusScreen#sendPlayback} 的目标位置来自
 * {@code MP4FocusScreen#currentProgressMillis()}：
 *
 * <pre>
 *   mediaProgress × selectedTrackDurationMillis()
 * </pre>
 *
 * 拖进度条时会先按下、后松开，中间隔着若干刻。如果这段时间里服务端把当前这首放完、
 * 换成了下一首（本模组一张碟占一条队列项，换曲不换队列下标，客户端连"下标没变"都察觉不到），
 * 松手时算出来的就是<b>上一首的位置</b>，而服务端会把它当成"下一首要播到哪儿"。
 *
 * <p>{@link Mp4TrackGuard} 在按下/松开两端各记一次曲目身份（由 {@code setScrubbingProgress}
 * 触发），中间变了就把这次操作标成作废，这里直接吃掉 —— 那首歌本来就已经播完了，
 * 这次 SEEK 没有任何意义。
 *
 * <p>只拦 {@code SEEK} 与 {@code START}：这两个才带"要播到哪儿"的语义。
 * {@code VOLUME} 的目标位置被服务端忽略，{@code RESTART} 本来就是从 0 开始，都不动。
 *
 * <h2>2. 关界面时复位拖动状态（顺带修上游一个坑）</h2>
 * 上游的 {@code scrubbingProgress} 只在 {@code mouseReleased} 里复位。玩家按着进度条
 * 用右键或 ESC 关界面时，那个方法不会被调用 —— 标志<b>永远留在 true</b>，
 * 于是 {@code MP4FocusState#renderTick} 里的
 * {@code if (active && playing && !scrubbingProgress)} 永远不成立，
 * {@code syncFocusedProgress()} 再也跑不了：进度条从此<b>冻结</b>在拖动时的位置，
 * 而且每次 {@code save()} 都在把这个冻结值推给服务端。
 *
 * <p>在 {@code onClose} <b>最前面</b>复位它（必须早于 {@code MP4Client#flushFocusedStateToServer}，
 * 否则关界面这一下就会把冻结值推出去一次）。
 */
@Mixin(targets = "com.zhongbai233.net_music_can_play_bili.gui.MP4FocusScreen")
public class MP4FocusScreenMixin {

    @Inject(method = "sendPlayback", at = @At("HEAD"), cancellable = true)
    private void discstudio$dropStaleControl(MP4PlaybackControlPacket.Action action, long targetMillis,
            CallbackInfo ci) {
        if (action != MP4PlaybackControlPacket.Action.SEEK && action != MP4PlaybackControlPacket.Action.START) {
            return;
        }
        if (Mp4TrackGuard.consumeScrubStale()) {
            ci.cancel();
        }
    }

    /**
     * 界面关闭：先清本模组的拖动簿记（让镜像重新受"换曲即作废"管辖），
     * 再把上游那个可能卡住的 {@code scrubbingProgress} 复位。
     */
    @Inject(method = "onClose", at = @At("HEAD"))
    private void discstudio$endScrubOnClose(CallbackInfo ci) {
        Mp4TrackGuard.cancelScrub();
        MP4FocusState.setScrubbingProgress(false);
    }
}
