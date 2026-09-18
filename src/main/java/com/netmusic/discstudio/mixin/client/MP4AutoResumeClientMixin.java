package com.netmusic.discstudio.mixin.client;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.netmusic.discstudio.bili.Mp4StartOffsetGuard;
import com.netmusic.discstudio.client.Mp4TrackGuard;
import com.zhongbai233.net_music_can_play_bili.client.MP4AutoResumeClient;
import com.zhongbai233.net_music_can_play_bili.client.MP4Client;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlayback;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.UUID;

/**
 * 拦住「把上一首的进度套到新曲子上」的那次自动恢复。
 *
 * <h2>症状</h2>
 * 一首歌放完跳到下一首时，进度条不从头开始而是直接顶在最后，于是一首接一首不停地跳、
 * 永远听不完一首。把界面收起来（关掉 GUI）照样发生 —— 这条路上唯一的发起者是本类
 * 注入的 {@code MP4AutoResumeClient#tick}，它跟界面开不开没有关系。
 *
 * <h2>目标位置从哪来</h2>
 * {@code MP4AutoResumeClient#targetMillis}：
 * <pre>
 *   targetMillis = round(state.progressPerMille() / 1000.0 × 曲长)
 * </pre>
 * <b>分子是刚放完那一首的百分比，分母是队列里这条现在这首新曲子的长度</b> —— 不是同一首歌。
 *
 * <h2>四次实机取证（别再退回阈值法）</h2>
 * <ol>
 *   <li>一开始按"千分比 ≥ 曲长的 9/10"拦。实机证明只盖住一半：漏掉
 *       {@code captured=156375ms（845‰ × 185s）} 这类中段值。</li>
 *   <li>改成"换曲后 5 秒窗口"。仍然漏 —— 直链异步解析实测 1~6 秒，
 *       窗口一过期照旧发包，日志里 {@code 146852 / 167954 / 101800…} 全在窗口外。</li>
 *   <li>查遍服务端与客户端其它发起者（重试路 {@code Mp4ClientMediaRetryPolicy}、
 *       完成策略 {@code MP4QueueCompletionPolicy}、服务端 5 条 {@code clampElapsed} 调用点）
 *       —— 日志里 {@code 音频流初始化失败} / {@code 重试} 各 0 次，全部排除。</li>
 *   <li>读存档确认：客户端镜像里的百分比就是<b>玩家快进时留下的那个</b>，
 *       而 {@code MP4Client#save} 之后<b>没有任何东西再刷新这份缓存</b>（界面关着，
 *       {@code save()} 不会被调用）。所以窗口必须靠"新会话起来了没有"关闭，
 *       不能靠计时器。</li>
 * </ol>
 *
 * <h2>判据</h2>
 * 两个<b>结构上就不可能对</b>的签名（见 {@link Mp4TrackGuard}）：
 * <ol>
 *   <li><b>换曲窗口内</b>：曲目身份（设备 ID + 队列下标 + 碟内曲目下标）变过，且新曲目的
 *       播放会话还没起来 → 整次 tick 取消。窗口由 {@link Mp4TrackGuard#noteSessionState}
 *       在"旧会话消失、新会话出现"时关闭，长度天然等于真实的解析+起播耗时。</li>
 *   <li><b>千分比高得像曲尾</b>：保留原有的 9/10 判据作为兜底。</li>
 * </ol>
 * 两者都不成立时（例如断线重连后服务端确实还停在曲子中段）照旧放行，续播行为不变。
 */
@Mixin(MP4AutoResumeClient.class)
public class MP4AutoResumeClientMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private static void discstudio$skipStaleResume(CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null) {
            return;
        }
        ItemStack stack = MP4Item.findAnyInInventory(minecraft.player);
        if (!(stack.getItem() instanceof MP4Item)) {
            return;
        }
        UUID deviceId = MP4Item.readDeviceId(stack);
        if (deviceId == null) {
            return;
        }
        MP4Item.State state = MP4Client.cachedStateFor(stack);
        int queueIndex = state.selectedQueueIndex();

        // 先观察身份（界面关着的时候只有这里在跑），顺带把"换曲窗口"支起来。
        Mp4TrackGuard.observeCurrent();

        boolean hasSession = ClientMediaPlayback.hasPlayback(deviceId);
        // 喂给守护：换曲窗口靠"会话先消失（旧会话退场）、再出现（新会话起播）"关闭。
        Mp4TrackGuard.noteSessionState(hasSession);
        if (hasSession) {
            // 本机还有播放会话时上游自己就会提前返回，不用我们管。
            return;
        }
        if (!state.playing()) {
            return;
        }
        List<ItemStack> queue = MP4Item.readQueue(stack);
        if (queueIndex < 0 || queueIndex >= queue.size()) {
            return;
        }
        @SuppressWarnings("null")
        ItemMusicCD.SongInfo songInfo = ItemMusicCD.getSongInfo(queue.get(queueIndex));
        int durationSeconds = songInfo != null ? Math.max(0, songInfo.songTime) : 0;
        if (durationSeconds <= 0) {
            return;
        }
        long durationMillis = durationSeconds * 1000L;
        long positionMillis = Math.max(0L,
                Math.round(state.progressPerMille() / 1000.0D * durationMillis));
        boolean retired = Mp4StartOffsetGuard.isRetiredProgress(positionMillis, durationSeconds);
        // 换曲窗口内，或者这份百分比自换曲以来一直没变过 —— 后者只能是上一首的残留。
        boolean switching = Mp4TrackGuard.isStaleMirror(state.progressPerMille());
        if (retired || switching) {
            Mp4StartOffsetGuard.logAutoResumeBlocked(positionMillis, durationSeconds, state.progressPerMille(),
                    retired, switching);
            ci.cancel();
        }
    }
}
