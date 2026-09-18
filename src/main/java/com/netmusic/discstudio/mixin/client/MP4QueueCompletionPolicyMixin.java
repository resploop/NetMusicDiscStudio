package com.netmusic.discstudio.mixin.client;

import com.netmusic.discstudio.client.album.Mp4AlbumView;
import com.netmusic.discstudio.client.album.Mp4RealState;
import com.zhongbai233.net_music_can_play_bili.client.MP4FocusState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 把 {@code MP4QueueCompletionPolicy}（"这一首放完了接下来放什么"）从
 * <b>显示行空间</b>掰回<b>真实队列空间</b>。
 * <p>
 * 上游这里直接读 {@link MP4FocusState#queueSize()} 与
 * {@link MP4FocusState#selectedQueueIndex()} 来判断"当前是不是最后一首"，
 * 而本模组为了让列表能折叠展示专辑，把这两个方法改成了显示行空间的值
 * （一行 = 一张专辑）。若不放行这一步，会出现两种错误：
 *
 * <ul>
 *   <li>判断错"最后一首"，导致顺序播放提前停住、或列表循环回到错误的曲目；</li>
 *   <li>更严重的是它内部的 {@code sendControl} 会把
 *       {@code MP4FocusState.selectedQueueIndex()} <b>发给服务端换源</b> ——
 *       发过去一个"显示行号"就会让服务端去播另一条队列项。</li>
 * </ul>
 *
 * <p>这里只替换这三处取值调用，其余逻辑（含它自己的守卫与 repeatMode 分支）
 * 完全交给上游原实现。队列里没有专辑时返回的就是上游原值，行为与打本模组之前一致。
 */
@Mixin(targets = "com.zhongbai233.net_music_can_play_bili.client.MP4QueueCompletionPolicy")
public class MP4QueueCompletionPolicyMixin {

    /** 真实队列长度。 */
    @Redirect(
            method = "onCompleted",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/zhongbai233/net_music_can_play_bili/client/MP4FocusState;queueSize()I"))
    private static int discstudio$realQueueSize() {
        return Mp4AlbumView.active() ? Mp4RealState.queueSize() : MP4FocusState.queueSize();
    }

    /** onCompleted 里判断"是不是最后一首"用的下标。 */
    @Redirect(
            method = "onCompleted",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/zhongbai233/net_music_can_play_bili/client/MP4FocusState;selectedQueueIndex()I"))
    private static int discstudio$realSelectedIndex() {
        return Mp4AlbumView.active() ? Mp4RealState.selectedQueueIndex() : MP4FocusState.selectedQueueIndex();
    }

    /** sendControl 要发给服务端的下标——必须是真实下标。 */
    @Redirect(
            method = "sendControl",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/zhongbai233/net_music_can_play_bili/client/MP4FocusState;selectedQueueIndex()I"))
    private static int discstudio$realSelectedIndexForSend() {
        return Mp4AlbumView.active() ? Mp4RealState.selectedQueueIndex() : MP4FocusState.selectedQueueIndex();
    }
}
