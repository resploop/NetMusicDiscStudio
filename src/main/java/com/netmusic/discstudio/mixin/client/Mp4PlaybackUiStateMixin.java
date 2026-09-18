package com.netmusic.discstudio.mixin.client;

import com.netmusic.discstudio.client.Mp4SeekHold;
import com.zhongbai233.net_music_can_play_bili.client.MP4PlaybackUiState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 拖完进度条后的一小段「别弹回去」窗口。
 *
 * <p>上游这个方法是"把进度条拉到本机正在播的位置"，每帧都跑。松开进度条的那一刻
 * SEEK 才刚发出去，服务端还在解析直链、客户端还没重开音频流，本机位置仍是拖动之前那个，
 * 于是进度条会立刻被拉回去 —— 玩家看到的就是"拖了没用"。
 *
 * <p>松手后由 {@link Mp4SeekHold} 开一个短窗口，窗口内跳过这次覆盖，
 * 让进度条停在玩家拖到的位置，等新状态到位后自然继续跟随。
 * 只影响进度条显示，不碰播放状态与控制包。
 */
@Mixin(MP4PlaybackUiState.class)
public class Mp4PlaybackUiStateMixin {

    @Inject(method = "syncFocusedProgress", at = @At("HEAD"), cancellable = true)
    private static void discstudio$holdProgressAfterSeek(CallbackInfo ci) {
        if (Mp4SeekHold.holding()) {
            ci.cancel();
        }
    }
}
