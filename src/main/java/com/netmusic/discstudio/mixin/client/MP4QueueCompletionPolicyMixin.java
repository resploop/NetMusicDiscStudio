package com.netmusic.discstudio.mixin.client;

import com.netmusic.discstudio.client.album.Mp4AlbumPlayback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * 「一首放完了接下来放什么」——把专辑的<b>专辑内推进</b>接进来。
 *
 * <p>上游 {@code MP4QueueCompletionPolicy#onCompleted} 只会把队列下标 ±1。
 * 但一张网络CD在队列里只占一条，它的"下一首"应该是<b>这张碟里的下一首</b>，
 * 而不是队列里的下一条碟；走到整张专辑的最后一首，才轮到队列层的
 * "下一条 / 回到队首 / 停止"。
 *
 * <p>这段决策全部在 {@link Mp4AlbumPlayback#advanceOnCompleted} 里：
 * 它只在"当前这条是多曲目专辑"时接管并返回 true（此时取消上游）；
 * 其余情况（非专辑条目、单曲循环、顺序播放到底）一律返回 false，
 * 完全交给上游原实现 —— 于是单曲循环重播、顺序停止、断线恢复等行为不变。
 */
@Mixin(targets = "com.zhongbai233.net_music_can_play_bili.client.MP4QueueCompletionPolicy")
public class MP4QueueCompletionPolicyMixin {

    @Inject(method = "onCompleted", at = @At("HEAD"), cancellable = true)
    private void discstudio$albumAdvance(UUID deviceId, String sessionId, CallbackInfo ci) {
        if (Mp4AlbumPlayback.advanceOnCompleted(deviceId, sessionId)) {
            ci.cancel();
        }
    }
}
