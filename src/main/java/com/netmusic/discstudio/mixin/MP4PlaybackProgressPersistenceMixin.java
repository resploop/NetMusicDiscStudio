package com.netmusic.discstudio.mixin;

import com.netmusic.discstudio.bili.Mp4SongStartGuard;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/**
 * 服务端"自动接管起播"那条路的收口：{@code MP4PlaybackProgressPersistence#targetMillis}。
 *
 * <p>javap 核过实机 0.7.10 的字节码：这个方法在<b>全仓只有一个调用点</b> ——
 * {@code MP4PlaybackSyncManager#startDiscovered} 第 584 行，紧接着 635 行
 * {@code clampElapsed(targetMillis, duration)} 起播、643 行
 * {@code PlaybackSessionId.of(deviceId + "-mp4-" + System.nanoTime())} 建会话。
 * 也就是说：<b>凡是服务端自己发起的起播位置，都从这里出</b>。
 *
 * <p>而它的取值顺序里有一项是"运行期进度"，那份进度<b>只按 deviceId 存、只记 queueIndex</b>：
 * 本模组一条队列项 = 一整张网络CD，碟内换曲时 queueIndex 不变，
 * 于是上一首播到哪儿就被当成下一首的起播位置（实测
 * {@code captured=48504ms} / {@code 22384ms} 全是这么来的）。
 *
 * <p>这里在返回值上做一次"归属"判定：这份位置属不属于<b>这次要播的那一首</b>？
 * 不属于（换歌了、或者刚切过歌还没起播）就改成 0 —— 也就是玩家要的"切歌时强制从头播放"。
 *
 * <p>目标类是<b>包私有 final 类、方法是非静态</b>，所以用 {@code targets} 字符串定位、
 * 处理器写成实例方法，参数为「原方法入参 + CallbackInfoReturnable」。
 */
@Mixin(targets = "com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackProgressPersistence")
public class MP4PlaybackProgressPersistenceMixin {

    @Inject(method = "targetMillis", at = @At("RETURN"), cancellable = true)
    private void discstudio$forceStartFromZero(ServerLevel level, UUID deviceId, ItemStack stack,
            MP4Item.State state, int queueIndex, CallbackInfoReturnable<Long> cir) {
        Long value = cir.getReturnValue();
        long offsetMillis = value == null ? 0L : value;
        long fixed = Mp4SongStartGuard.sanitizeOnTarget(deviceId,
                Mp4SongStartGuard.songKeyOf(stack, queueIndex), offsetMillis);
        if (fixed != offsetMillis) {
            cir.setReturnValue(fixed);
        }
    }
}
