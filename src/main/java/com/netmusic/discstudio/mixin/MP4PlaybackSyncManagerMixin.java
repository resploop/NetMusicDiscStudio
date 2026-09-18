package com.netmusic.discstudio.mixin;

import com.netmusic.discstudio.bili.Mp4SongStartGuard;
import com.netmusic.discstudio.bili.Mp4StartOffsetGuard;
import com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackSyncManager;
import com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackSyncPacket;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 服务端「会话从哪儿起播」的两个收口。
 *
 * <h2>一、{@code clampElapsed} —— 只堵"落在曲尾"</h2>
 * 上游里凡是把「上次播到哪」换算成实际起播毫秒的地方
 * ——{@code start}（起播/续播）、{@code resumeExisting}、{@code refreshActiveSession}、
 * {@code applyRetryResolved}（流失败重试）、{@code lambda$startDiscovered$0}——
 * <b>全部</b>经过这个方法：
 *
 * <pre>
 *   private static long clampElapsed(long elapsedMillis, int durationSeconds) {
 *       long max = Math.max(0L, Math.max(1, durationSeconds) * 1000L - 50L);
 *       return Math.max(0L, Math.min(max, elapsedMillis));
 *   }
 * </pre>
 *
 * <p>它只做"夹到 [0, 曲长-50ms]"。"上一首播到结尾"写下的进度正好顶在这个上界，
 * 于是新曲目被定位到"结尾前 50ms"→ 立刻判定放完 → 推进 → 无限换歌。
 * 这里覆盖返回值：只要落进曲子最后几秒，就按"这首已经放完"处理（判定见 {@link Mp4StartOffsetGuard}）。
 *
 * <p><b>注意这个口子只负责"曲尾"这一类</b>：像 {@code 48504ms}（一首 61 秒曲子播到 48.5 秒处）
 * 离结尾还有 12 秒，指望不上它，得靠下面第二个口子。
 *
 * <h2>二、{@code start} —— 切歌后一律从头</h2>
 * {@code MP4PlaybackSyncManager#start(ServerPlayer, MP4PlaybackSyncPacket)} 是客户端控制包
 * （SEEK / START，经 {@code MP4PlaybackControlPacket#applyResolvedPlayback} 转成同步包）
 * 与服务端 {@code ServerMediaPlayback} 共同的<b>唯一建会话入口</b>。
 * 在这里做一次判定：本设备是不是刚切过歌、而这次却想从中间某处起播？是就作废成 0
 * （判据见 {@link Mp4SongStartGuard#sanitizeOnStart}）。新曲目一旦真的从头起播，
 * 这份"待从头"标记就自动关闭，之后玩家自己拖的进度条不受影响。
 *
 * <p>判定命中时 {@code cancel} 掉本次调用、用 {@code elapsedMillis = 0} 重建同内容的数据包重新进入。
 * 复入时 offset 已是 0，判定必然放行，所以不会递归。
 */
@Mixin(targets = "com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackSyncManager")
public class MP4PlaybackSyncManagerMixin {

    @Inject(method = "clampElapsed", at = @At("RETURN"), cancellable = true)
    private static void discstudio$restartInsteadOfStartingAtTail(long elapsedMillis, int durationSeconds,
            CallbackInfoReturnable<Long> cir) {
        cir.setReturnValue(Mp4StartOffsetGuard.sanitize(cir.getReturnValue(), durationSeconds));
    }

    @Inject(method = "start", at = @At("HEAD"), cancellable = true)
    private static void discstudio$forceZeroAfterSwitch(ServerPlayer owner, MP4PlaybackSyncPacket packet,
            CallbackInfo ci) {
        if (owner == null || packet == null) {
            return;
        }
        long requested = packet.elapsedMillis();
        long fixed = Mp4SongStartGuard.sanitizeOnStart(packet.sourceId(), requested);
        if (fixed == requested) {
            return;
        }
        ci.cancel();
        MP4PlaybackSyncManager.start(owner, new MP4PlaybackSyncPacket(
                packet.ownerId(), packet.sourceId(), packet.sourceType(), packet.sourceEntityId(),
                packet.sourceX(), packet.sourceY(), packet.sourceZ(), packet.playing(), packet.queueIndex(),
                packet.playUrl(), packet.rawUrl(), packet.songName(), packet.durationSeconds(),
                packet.volumePerMille(), packet.sessionId(), fixed, packet.headphoneRouted(),
                packet.areaAudioZone()));
    }
}
