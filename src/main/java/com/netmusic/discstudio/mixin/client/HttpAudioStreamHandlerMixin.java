package com.netmusic.discstudio.mixin.client;

import com.github.tartaricacid.netmusic.client.api.implement.DirectHttpHandler;
import com.github.tartaricacid.netmusic.client.api.implement.NetEaseHttpHandler;
import com.netmusic.discstudio.client.Mp4RangeSeek;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackRequest;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.net.URL;

/**
 * 让"快进"变成真的快进：不再把整首歌重下一遍。
 *
 * <h2>上游怎么做的（以及为什么慢到没声音）</h2>
 * {@code MP4PlaybackSyncManager} 给客户端的起播位置只体现在
 * {@code PlaybackRequest#elapsedMillis} 上（"请从第 N 毫秒开始放"）。
 * 客户端拿到手之后，音频流仍然是<b>从文件开头</b>打开的
 * （{@link NetEaseHttpHandler#handle(URL)} 让 {@code ChunkedAudioStream} 从第 0 字节顺次读），
 * 所谓"定位"全靠 {@code PcmStartupSeekPolicy} 把 PCM <b>一路解码着丢弃</b>到第 N 毫秒。
 *
 * <p>实测（2026-09-19 的 debug.log）：
 * <pre>
 *   23:57:59  玩家拖到 81.6 秒，服务端建会话 mp4-43837821575200
 *   23:58:52  才出现「HTTP 音频起播追赶完成」setup=52984ms，effective=132613ms
 * </pre>
 * 也就是说：<b>53 秒完全没有声音</b>，而且真正起播的位置是 132 秒（追赶期间目标被墙钟时间
 * 一直往前推）。更糟的是目标一旦越过文件末尾，{@code requireReadablePcm} 会抛
 * {@code EOFException: no decoded PCM after HTTP seek}，整首曲子<b>彻底静音</b>
 * —— 进度条还在走、音符粒子还在飞，因为"在播"是服务端说的。
 *
 * <h2>这里做什么</h2>
 * 网易云的直链支持 {@code Range: bytes=N-}（实测稳定返回 {@code 206} + {@code Content-Range}），
 * 而文件是恒定码率 —— 于是可以直接向 CDN 要"从第 N 秒开始"的那一段字节，
 * 让上游的追赶只剩几十毫秒的工作量。三处协作（实现见 {@link Mp4RangeSeek}）：
 *
 * <ol>
 *   <li>{@code fallbackHttpStream} 开头记下这次开流对应的 {@link PlaybackRequest}；</li>
 *   <li>把"从头发起的那个流"换成"从目标字节发起的流"（失败就原样调用上游的
 *       {@code NetEaseHttpHandler#handle}，行为与没打这个模组时完全一致）；</li>
 *   <li>把交给追赶策略的偏移减去已经跳过去的秒数，并夹到「曲尾前 1.5 秒」以内
 *       —— 后半句顺手堵死"追赶越过文件末尾 → EOF → 整首没声音"。</li>
 * </ol>
 *
 * <p>目标类是被 netmusic 的 {@code AudioStreamHandlerManager} 选中的处理器（优先级 10 +
 * {@code bili} 那套把优先级压到更低），对网易云直链由它负责开流。
 * 不在网易云主机上的（B 站等）以及短距离快进，全部原样让路上游。
 */
@Mixin(targets = "com.zhongbai233.net_music_can_play_bili.bili.HttpAudioStreamHandler")
public class HttpAudioStreamHandlerMixin {

    /**
     * 记下这次开流对应的播放请求 —— 后面那个"改偏移"的注入点拿不到它，
     * 只能靠同一线程上的这次调用把上下文传过去。
     *
     * <p><b>注意回调类型</b>：{@code fallbackHttpStream} 声明是
     * {@code private static AudioInputStream fallbackHttpStream(URL, PlaybackRequest,
     * UnsupportedAudioFileException)} —— <b>有返回值</b>，所以这里必须是
     * {@link CallbackInfoReturnable}。写成 {@code CallbackInfo} 会在 APPLY 阶段抛
     * {@code InvalidInjectionException: ... CallbackInfoReturnable is required!}，
     * 而 {@code defaultRequire=1} 会让整个游戏起不来（2026-09-19 踩过一次）。
     */
    @Inject(method = "fallbackHttpStream", at = @At("HEAD"))
    private static void discstudio$beginRangeSeekCall(URL url, PlaybackRequest request,
            UnsupportedAudioFileException cause, CallbackInfoReturnable<AudioInputStream> cir) {
        Mp4RangeSeek.beginCall(request);
    }

    /**
     * 把"从第 0 字节开始的流"换成"从目标字节开始的流"。
     * <p>
     * 这里是整条链上唯一能决定"流从哪儿开始"的地方：再往后的 {@code toPcmStream} /
     * {@code PcmStartupSeekPolicy} 拿到的已经是解码后的 PCM，只能靠丢弃来推进。
     *
     * <p>{@link Mp4RangeSeek#tryOpenRanged} 返回 {@code null}（不是网易云主机、距离太近、
     * 拿不到长度、服务端没理会 Range、找不到帧同步头……）时，原样调用上游的处理器，
     * 于是这类情况的行为与打补丁前逐字节一致。
     */
    @Redirect(method = "fallbackHttpStream",
            at = @At(value = "INVOKE",
                    target = "Lcom/github/tartaricacid/netmusic/client/api/implement/NetEaseHttpHandler;handle(Ljava/net/URL;)Ljavax/sound/sampled/AudioInputStream;"))
    private static AudioInputStream discstudio$openAtSeekPosition(NetEaseHttpHandler handler, URL url)
            throws UnsupportedAudioFileException, IOException {
        AudioInputStream ranged = Mp4RangeSeek.tryOpenRanged(url, Mp4RangeSeek.currentRequest());
        return ranged != null ? ranged : handler.handle(url);
    }

    /**
     * 同上，但管的是<b>实际会走的</b>那条分支。
     *
     * <p>⚠️ 2026-09-19 踩过的坑：{@code fallbackHttpStream} 里有两条并列的"开流"分支 ——
     * <pre>
     *   stream = new NetEaseHttpHandler().canHandle(url)
     *          ? new NetEaseHttpHandler().handle(url)     // 只认 host.contains("music.163.com")
     *          : new DirectHttpHandler().handle(url);     // ← 网易云 CDN 直链走这条
     * </pre>
     * 而网易云真正的直链是 {@code http://m10.music.126.net/...}（{@code NetEaseMusic.getHost()}
     * 返回的却是 {@code music.163.com}），{@code canHandle} 必然 false
     * —— 只 redirect 左边那条时，本模组的代码<b>一次都不会被调用</b>：
     * 日志里 {@code Mixing ... HttpAudioStreamHandlerMixin} 正常、
     * {@code HTTP 音频起播追赶完成 setup=56025ms} 照旧，只是完全没有 {@code MP4 快进} 字样。
     * <p><b>教训：{@code @Redirect} 之前要把这个调用点的<b>所有并列分支</b>都数出来，
     * 不能只挑名字最像的那个类。</b>
     */
    @Redirect(method = "fallbackHttpStream",
            at = @At(value = "INVOKE",
                    target = "Lcom/github/tartaricacid/netmusic/client/api/implement/DirectHttpHandler;handle(Ljava/net/URL;)Ljavax/sound/sampled/AudioInputStream;"))
    private static AudioInputStream discstudio$openAtSeekPositionDirect(DirectHttpHandler handler, URL url)
            throws UnsupportedAudioFileException, IOException {
        AudioInputStream ranged = Mp4RangeSeek.tryOpenRanged(url, Mp4RangeSeek.currentRequest());
        return ranged != null ? ranged : handler.handle(url);
    }

    /**
     * 交给追赶策略的偏移 = 原本的偏移 − 我们已经在字节层面跳过去的秒数，
     * 并且夹到「曲尾前 1.5 秒」以内。
     *
     * <p>为什么必须减：流已经从那一段开始了，再让追赶策略跳一遍就会播到目标之后去。
     * <p>为什么必须夹：追赶越过文件末尾时 {@code skip} 读空，紧接着
     * {@code requireReadablePcm} 抛 {@code EOFException}，结果是这首曲子一点声音都没有。
     * 宁可早 1.5 秒，也不要静默。
     */
    @ModifyArg(method = "fallbackHttpStream",
            at = @At(value = "INVOKE",
                    target = "Lcom/zhongbai233/net_music_can_play_bili/bili/HttpAudioStreamHandler;openModernFallbackStream(Ljavax/sound/sampled/AudioInputStream;Lcom/zhongbai233/net_music_can_play_bili/media/sync/PlaybackRequest;F)Ljavax/sound/sampled/AudioInputStream;"),
            index = 2)
    private static float discstudio$trimStartOffset(float offsetSeconds) {
        return Mp4RangeSeek.adjustOffsetSeconds(offsetSeconds);
    }
}
