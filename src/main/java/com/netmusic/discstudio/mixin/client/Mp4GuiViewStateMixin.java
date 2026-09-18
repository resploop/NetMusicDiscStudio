package com.netmusic.discstudio.mixin.client;

import com.netmusic.discstudio.client.album.Mp4AlbumView;
import com.zhongbai233.net_music_can_play_bili.client.MP4FocusState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * MP4 离屏 GUI 的「显示层折叠」。
 *
 * <p>队列模型是「一张网络CD = 一条队列项」，所以 {@link MP4FocusState} 里的
 * 队列长度 / 选中下标 / 滚动偏移<b>始终是真实值</b> —— 发给服务端的控制包
 * （SEEK / RESTART / VOLUME）自然就带的是真实下标，不会出现"拖进度条却换错源"的问题。
 *
 * <p>但列表要能展示"进专辑看曲目"，就必须让渲染器看到一套<b>显示空间</b>的坐标：
 * 打开某张专辑时，显示尺寸 = 曲目数、选中行 = 该专辑当前播的那一首、标题 = 曲目名。
 * 这套换算只发生在渲染器每帧取的快照上，也就是这里：
 * <ul>
 *   <li>{@code capture} 里三处 {@code MP4FocusState} 取值的重定向；</li>
 *   <li>{@code captureQueueTitles} / {@code currentSongTitle} 里曲目标题的重定向。</li>
 * </ul>
 * 队列里没有专辑（{@link Mp4AlbumView#active()} 为 false）时，每一处都原样返回上游的值，
 * 界面的行为与打本模组之前完全一致。
 *
 * <p>顺带修正上游 {@code playbackModeLabel} 的<b>标签错位</b>：
 * 上游把 {@code repeatMode == 1} 标成"列表循环"、{@code 2} 标成"单曲循环"，
 * 但 {@code MP4QueueCompletionPolicy} 里 1 是重播当前曲（单曲）、2 是回到队首（列表），
 * 两者正好相反 —— 于是玩家点了"列表循环"却发现一直在单曲。这里按真实行为给出标签。
 */
@Mixin(targets = "com.zhongbai233.net_music_can_play_bili.client.renderer.item.MP4GuiViewState")
public class Mp4GuiViewStateMixin {

    // ───────────────────── 显示空间：队列尺寸 / 选中行 / 滚动偏移 ─────────────────────

    /** 列表行数：列表层 = 队列条数；专辑内 = 1（返回行）+ 曲目数。 */
    @Redirect(
            method = "capture",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/zhongbai233/net_music_can_play_bili/client/MP4FocusState;queueSize()I"))
    private static int discstudio$displayQueueSize() {
        return Mp4AlbumView.active() ? Mp4AlbumView.displaySize() : MP4FocusState.queueSize();
    }

    /** 高亮行：列表层 = 真实队列下标；专辑内 = 该专辑当前播的那一首所在行。 */
    @Redirect(
            method = "capture",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/zhongbai233/net_music_can_play_bili/client/MP4FocusState;selectedQueueIndex()I"))
    private static int discstudio$displaySelectedIndex() {
        return Mp4AlbumView.active()
                ? Mp4AlbumView.displayIndexOf(MP4FocusState.selectedQueueIndex())
                : MP4FocusState.selectedQueueIndex();
    }

    /** 列表滚动：视图自己维护一套（显示空间）偏移。 */
    @Redirect(
            method = "capture",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/zhongbai233/net_music_can_play_bili/client/MP4FocusState;queueScrollOffset()I"))
    private static int discstudio$displayScrollOffset() {
        return Mp4AlbumView.active() ? Mp4AlbumView.scrollOffset() : MP4FocusState.queueScrollOffset();
    }

    // ───────────────────── 显示空间：可见行的标题 ─────────────────────

    /**
     * 渲染器构造"可见行标题表"以及取当前曲名时都会调这里。
     * <p>
     * 传进来的已是显示行号（{@code capture} 里两处偏移都换成了显示空间），
     * 直接交给视图映射成 {@code [专辑] 名称 (N首) >} / {@code 3. 曲目名} / {@code < 返回 · ...}。
     */
    @Redirect(
            method = {"captureQueueTitles", "currentSongTitle"},
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/zhongbai233/net_music_can_play_bili/client/MP4FocusState;queueTitle(I)Ljava/lang/String;"))
    private static String discstudio$displayTitle(int row) {
        return Mp4AlbumView.active() ? Mp4AlbumView.displayTitle(row) : MP4FocusState.queueTitle(row);
    }

    // ───────────────────── 播放模式标签：修正上游的反向映射 ─────────────────────

    @Inject(method = "playbackModeLabel", at = @At("RETURN"), cancellable = true)
    private void discstudio$playbackModeLabel(CallbackInfoReturnable<String> cir) {
        if (MP4FocusState.shuffle()) {
            cir.setReturnValue("随机");
            return;
        }
        cir.setReturnValue(switch (MP4FocusState.repeatMode()) {
            case 1 -> "单曲循环";   // 上游 policy：repeatMode == 1 → 重播当前曲
            case 2 -> "列表循环";   // 上游 policy：repeatMode == 2 → 播完回到队首
            default -> "顺序";
        });
    }
}
