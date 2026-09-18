package com.netmusic.discstudio.client;

import com.github.tartaricacid.netmusic.NetMusic;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.Mp3FrameSync;
import com.zhongbai233.net_music_can_play_bili.media.stream.HttpRangeHeaders;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackRequest;
import org.slf4j.Logger;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * 「快进」不再把整首歌重下一遍 —— 直接向 CDN 要「从第 N 秒开始」的那一段字节。
 *
 * <h2>问题（2026-09-19 实机取证）</h2>
 * 上游对网易云的直链没有"按字节定位"这条路：{@code HttpAudioStreamHandler} 拿到的是
 * <b>从文件开头</b>开始的流，所谓"seek"是靠 {@code PcmStartupSeekPolicy} 把 PCM
 * <b>一路解码着丢弃</b>到目标位置（{@code skip()}）。于是：
 *
 * <pre>
 *   拖到 81.6 秒 → 必须把前 81.6 秒的音频全部下载+解码完才能出声
 *   23:57:59 建会话   23:58:52 才出声   setup=52984ms   ← 中间 53 秒完全没有声音
 * </pre>
 *
 * <p>而且那段"追赶"期间还会把流逝的墙钟时间持续加到目标上，于是实测
 * {@code captured=81.603s → effective=132.613s}：玩家拖到 81 秒，实际从 132 秒开始放。
 * 更糟的是目标一旦越过文件末尾，{@code requireReadablePcm} 会抛
 * {@code EOFException: no decoded PCM after HTTP seek} —— <b>整首曲子彻底没有声音</b>
 * （本次会话 7 次）。进度条照走、音符粒子照飞，因为"在播"是服务端说的，客户端只是没出声。
 *
 * <h2>为什么这条路走得通</h2>
 * 实测网易云 exhigh 直链（{@code *.music.126.net}）：
 * <ul>
 *   <li>{@code Accept-Ranges: bytes}，{@code Range: bytes=N-} 稳定返回
 *       {@code 206 Partial Content} + {@code Content-Range}；</li>
 *   <li>文件是<b>恒定码率</b> 320 kbps（在 25%、50%、75%、99% 四处采样都是 320），
 *       ID3 标签只有几百字节 —— 于是「字节 ↔ 时间」可以精确换算。</li>
 * </ul>
 * 所以只要算出目标时间对应的字节、拉那一段、对齐到 MP3 帧同步头，就能<b>立刻</b>从
 * 玩家拖到的位置出声。
 *
 * <h2>接线方式（配合 {@code HttpAudioStreamHandlerMixin}）</h2>
 * <ol>
 *   <li>上游每次要开流时 {@link #beginCall} 记下这次的 {@link PlaybackRequest}；</li>
 *   <li>{@link #tryOpenRanged} 顶替"从头开始的那个流"；成功时把落点秒数记下来；</li>
 *   <li>{@link #adjustOffsetSeconds} 把交给上游追赶策略的偏移减去落点
 *       —— 流已经从那一段开始了，不能再跳一遍。</li>
 * </ol>
 * 任何一步不适用或失败都返回 null / 原值，调用方原样走上游逻辑，行为与没打这个模组时一致。
 *
 * <p><b>顺带堵住"没声音"</b>：{@link #adjustOffsetSeconds} 还会把偏移夹到「曲尾前 1.5 秒」。
 * 追赶一旦越过文件末尾就是 EOF，而上游对 EOF 的处理是整首曲子没有声音 —— 宁可早 1.5 秒，
 * 也不要静默。
 */
public final class Mp4RangeSeek {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 只对网易云的直链做这件事：其它主机（B 站等）上游自己有另一套区间处理。 */
    private static final String[] NET_EASE_HOST_SUFFIXES = {".music.126.net", ".music.163.com", ".126.net"};

    /** 短距离快进上游本来就够快（几十毫秒），不去碰。 */
    private static final double MIN_SEEK_SECONDS = 8.0;

    /** 太短的曲子（试听片段之类）不折腾，换算误差占比也会偏大。 */
    private static final double MIN_TRACK_SECONDS = 30.0;

    /** 网易云 exhigh（320 kbps）约 40 KB/s；落在这个区间才当它是正常 mp3，否则不猜。 */
    private static final double MIN_BYTES_PER_SECOND = 8_000.0;
    private static final double MAX_BYTES_PER_SECOND = 72_000.0;

    /** 帧码率与"总长度÷曲长"允许的偏差：超过就说明不是恒定码率，线性换算会错。 */
    private static final double RATE_TOLERANCE = 0.2;

    /** 探测窗口：装得下 ID3 标签和第一个 MP3 帧。 */
    private static final int PROBE_BYTES = 256 * 1024;

    /** 对齐窗口：只要够找到下一个帧同步头（一帧最大 1 KB 多）。 */
    private static final int ALIGN_BYTES = 32 * 1024;

    /** 起播位置最多夹到「曲尾前」这么多毫秒。 */
    private static final long TAIL_MARGIN_MILLIS = 1_500L;

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private Mp4RangeSeek() {
    }

    /** 默认走直连（与游戏里其它请求一致；Java 的 HttpClient 默认不读环境变量里的代理）。 */
    private static final class Client {
        static final HttpClient INSTANCE = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(12))
                .build();
    }

    // ───────────────────── 一次开流的上下文 ─────────────────────

    /**
     * 一次 {@code fallbackHttpStream} 调用期间在同一线程上传递的上下文。
     * <p>
     * 两个注入点需要共享"这次流开到了哪一秒"：替换流的那个点知道，改偏移的那个点要用。
     * 每次开流都新建一个，所以不会串味。
     */
    private static final class Call {
        final PlaybackRequest request;
        double landedSeconds;

        Call(PlaybackRequest request) {
            this.request = request;
        }
    }

    private static final ThreadLocal<Call> CURRENT = new ThreadLocal<>();

    /** 上游每次要开流时调一次（在 {@code fallbackHttpStream} 最前面）。 */
    public static void beginCall(PlaybackRequest request) {
        CURRENT.set(new Call(request));
    }

    /** 这次开流对应的播放请求；不在开流流程里时返回 null。 */
    public static PlaybackRequest currentRequest() {
        Call call = CURRENT.get();
        return call == null ? null : call.request;
    }

    /**
     * 把交给上游追赶策略的偏移整理成真正还需要跳的距离。
     *
     * <p>① 减去我们已经在字节层面跳过去的那一段（那一秒之前的音频根本没下载）；
     * ② 夹到「曲尾前 1.5 秒」以内 —— 追赶越过文件末尾就是 EOF，而上游会把
     * {@code EOFException} 一路抛到声音创建失败，结果是<b>整首曲子没有声音</b>。
     *
     * @param offsetSeconds 上游原本要交给追赶策略的偏移（秒）
     * @return 调整后的偏移（秒），不会小于 0
     */
    public static float adjustOffsetSeconds(float offsetSeconds) {
        Call call = CURRENT.get();
        if (call == null) {
            return offsetSeconds;
        }
        double landed = call.landedSeconds;
        call.landedSeconds = 0.0;
        double adjusted = offsetSeconds - landed;
        double totalSeconds = call.request == null ? 0.0 : call.request.totalMillis() / 1000.0;
        if (totalSeconds > 3.0) {
            adjusted = Math.min(adjusted, totalSeconds - TAIL_MARGIN_MILLIS / 1000.0 - landed);
        }
        if (adjusted < 0.0) {
            adjusted = 0.0;
        }
        return (float) adjusted;
    }

    // ───────────────────── 按字节区间开流 ─────────────────────

    /**
     * 试着按字节区间打开这段音频。
     *
     * @return 已经定位到目标位置的流；不适用或失败时返回 {@code null}，调用方原样走上游
     */
    public static AudioInputStream tryOpenRanged(URL url, PlaybackRequest request) {
        if (url == null || request == null) {
            return null;
        }
        try {
            return openRanged(url, request);
        } catch (Throwable t) {
            LOGGER.debug("MP4 快进：按字节定位抛异常，退回上游的从头追赶（{}）", t.toString());
            return null;
        }
    }

    private static AudioInputStream openRanged(URL url, PlaybackRequest request)
            throws IOException, InterruptedException, UnsupportedAudioFileException {
        if (!isNetEaseHost(url)) {
            return skip(url, "不是网易云主机");
        }
        double totalSeconds = request.totalMillis() / 1000.0;
        double targetSeconds = request.elapsedMillis() / 1000.0;
        if (totalSeconds <= MIN_TRACK_SECONDS) {
            return skip(url, "曲长只有 " + totalSeconds + " 秒，上游本来就追得上");
        }
        if (targetSeconds < MIN_SEEK_SECONDS) {
            return skip(url, "只快进了 " + targetSeconds + " 秒，上游本来就追得上");
        }
        if (targetSeconds >= totalSeconds - 1.0) {
            // 已经贴着曲尾了，让上游的夹取逻辑去处理，别在这里猜字节。
            return skip(url, "目标 " + targetSeconds + " 秒已贴曲尾（曲长 " + totalSeconds + " 秒）");
        }

        // ① 探头部：总长度、ID3 之后第一个 MP3 帧的位置。
        Probe head = fetch(buildRequest(url, 0L, PROBE_BYTES - 1L), PROBE_BYTES);
        if (head.status() != 200 && head.status() != 206) {
            return skip(url, "探测请求返回 HTTP " + head.status());
        }
        long totalBytes = totalBytes(head);
        if (totalBytes <= 0L) {
            return skip(url, "拿不到文件总长度");
        }
        byte[] headBytes = head.body();
        int firstFrame = Mp3FrameSync.findFrameSync(headBytes, headBytes.length);
        if (firstFrame < 0) {
            return skip(url, "头部 " + headBytes.length + " 字节里没有 MP3 帧同步头（多半不是 mp3）");
        }
        Mp3FrameSync.Frame frame = Mp3FrameSync.parseFrame(headBytes, firstFrame, headBytes.length);
        if (frame == null) {
            return skip(url, "首个帧头解析失败");
        }

        // ② 恒定码率换算：字节/秒 = (总长度 - 标签头) / 曲长。
        double bytesPerSecond = (totalBytes - firstFrame) / totalSeconds;
        if (bytesPerSecond < MIN_BYTES_PER_SECOND || bytesPerSecond > MAX_BYTES_PER_SECOND) {
            return skip(url, "平均码率 " + Math.round(bytesPerSecond / 1000.0) + " KB/s 不在 "
                    + Math.round(MIN_BYTES_PER_SECOND / 1000.0) + "~"
                    + Math.round(MAX_BYTES_PER_SECOND / 1000.0) + " 区间");
        }
        double frameBytesPerSecond = frameBytesPerSecond(frame);
        if (frameBytesPerSecond > 0.0
                && Math.abs(frameBytesPerSecond - bytesPerSecond) > bytesPerSecond * RATE_TOLERANCE) {
            // 首帧码率和平均码率对不上 ⇒ 多半是变码率文件，线性换算会偏出去很远，不猜。
            return skip(url, "疑似变码率（首帧 " + Math.round(frameBytesPerSecond / 1000.0)
                    + " KB/s vs 平均 " + Math.round(bytesPerSecond / 1000.0) + " KB/s）");
        }

        long byteOffset = firstFrame + Math.round(targetSeconds * bytesPerSecond);
        byteOffset = Math.max(firstFrame, Math.min(byteOffset, totalBytes - ALIGN_BYTES));

        // ③ 从目标字节拉流，并对齐到帧同步头。
        HttpResponse<InputStream> response = Client.INSTANCE.send(
                buildRequest(url, byteOffset, -1L), HttpResponse.BodyHandlers.ofInputStream());
        int status = response.statusCode();
        InputStream raw = response.body();
        if (status != 200 && status != 206) {
            closeQuietly(raw);
            return skip(url, "区间请求返回 HTTP " + status);
        }

        byte[] window;
        try {
            window = raw.readNBytes(ALIGN_BYTES);
        } catch (IOException e) {
            closeQuietly(raw);
            throw e;
        }

        HttpRangeHeaders.ContentRange range = HttpRangeHeaders.parseContentRange(
                response.headers().firstValue("Content-Range").orElse(null));
        // 必须由服务端亲口告诉我们"这段是从第几个字节开始的"。读不到就一律放弃 ——
        // 绝不能假设它一定从 byteOffset 开始：万一服务端没理会 Range（把整个文件回来了），
        // 我们会把"音频其实在开头"当成"已经在目标位置"，音画就彻底错位了。
        long windowStart = range != null && range.isKnown() ? range.start() : -1L;
        if (windowStart <= 0L) {
            closeQuietly(raw);
            return skip(url, "服务端没有回可解析的 Content-Range（不敢假设流从目标字节开始）");
        }

        int sync = Mp3FrameSync.findFrameSync(window, window.length);
        if (sync < 0) {
            closeQuietly(raw);
            return skip(url, "区间前 " + window.length + " 字节里没有 MP3 帧同步头");
        }
        Mp3FrameSync.Frame landed = Mp3FrameSync.parseFrame(window, sync, window.length);
        if (landed == null || landed.sampleRate() != frame.sampleRate()) {
            closeQuietly(raw);
            return skip(url, "落点帧头解析失败或采样率与头部不一致");
        }

        long landedBytes = windowStart + sync;
        double landedSeconds = (landedBytes - firstFrame) / bytesPerSecond;
        if (landedSeconds < 0.0 || landedSeconds > targetSeconds + 1.5) {
            closeQuietly(raw);
            return skip(url, "落点 " + landedSeconds + " 秒偏离目标 " + targetSeconds + " 秒太远");
        }

        InputStream stream = new BufferedInputStream(new SequenceInputStream(
                new ByteArrayInputStream(window, sync, window.length - sync), raw), 262144);
        AudioInputStream audio;
        try {
            audio = AudioSystem.getAudioInputStream(stream);
        } catch (Throwable t) {
            closeQuietly(stream);
            throw t;
        }

        Call call = CURRENT.get();
        if (call != null) {
            call.landedSeconds = landedSeconds;
        }
        LOGGER.info("MP4 快进：不重下整首，直接从第 {} 秒起播（拖到 {} 秒，曲长 {} 秒，码率 {} KB/s）",
                String.format(Locale.ROOT, "%.2f", landedSeconds),
                String.format(Locale.ROOT, "%.2f", targetSeconds),
                String.format(Locale.ROOT, "%.1f", totalSeconds),
                Math.round(bytesPerSecond / 1000.0));
        return audio;
    }

    private record Probe(int status, java.net.http.HttpHeaders headers, byte[] body) {
    }

    private static Probe fetch(HttpRequest request, int limit) throws IOException, InterruptedException {
        HttpResponse<InputStream> response =
                Client.INSTANCE.send(request, HttpResponse.BodyHandlers.ofInputStream());
        byte[] body;
        try (InputStream in = response.body()) {
            body = in == null ? new byte[0] : in.readNBytes(limit);
        }
        return new Probe(response.statusCode(), response.headers(), body);
    }

    /**
     * 造一个带 {@code Range} 的请求。
     *
     * @param start         起始字节
     * @param endInclusive  结束字节；小于 {@code start} 表示"一直到文件末尾"
     */
    private static HttpRequest buildRequest(URL url, long start, long endInclusive) {
        URL target = stripFragment(url);
        HttpRequest.Builder builder = endInclusive >= start
                ? HttpRangeHeaders.boundedRangeRequest(target, start, endInclusive, TIMEOUT)
                : HttpRangeHeaders.rangeRequest(target, start, false, TIMEOUT);
        applyNetEaseHeaders(builder);
        return builder.build();
    }

    /**
     * 把上游 {@code NetEaseHttpHandler} 用的那套请求头（UA、Cookie 等）照样带上，
     * 保证 CDN 看到的请求和平时一模一样。
     */
    private static void applyNetEaseHeaders(HttpRequest.Builder builder) {
        try {
            for (Map.Entry<String, String> entry : NetMusic.NET_EASE_WEB_API.getRequestPropertyData().entrySet()) {
                String name = entry.getKey();
                String value = entry.getValue();
                if (name == null || value == null || name.isBlank() || "range".equalsIgnoreCase(name)) {
                    continue;
                }
                builder.header(name, value);
            }
        } catch (Throwable ignored) {
            // 带不上也无所谓：放行与否取决于 URL 里的签名，这些头只是保持一致。
        }
    }

    /** 去掉 {@code #nmb_request=...} 这类片段：HTTP 不会发送它，留着只会让 URI 解析别扭。 */
    private static URL stripFragment(URL url) {
        String text = url.toString();
        int hash = text.indexOf('#');
        if (hash < 0) {
            return url;
        }
        try {
            return URI.create(text.substring(0, hash)).toURL();
        } catch (RuntimeException | MalformedURLException e) {
            return url;
        }
    }

    /** 文件总长度：区间响应优先读 {@code Content-Range}，退回 {@code Content-Length}。 */    private static long totalBytes(Probe probe) {
        HttpRangeHeaders.ContentRange range = HttpRangeHeaders.parseContentRange(
                probe.headers().firstValue("Content-Range").orElse(null));
        if (range != null && range.hasKnownTotalLength()) {
            return range.totalLength();
        }
        return probe.headers().firstValue("Content-Length")
                .map(value -> {
                    try {
                        return Long.parseLong(value.trim());
                    } catch (NumberFormatException e) {
                        return 0L;
                    }
                })
                .orElse(0L);
    }

    /** 由帧头推出的字节/秒：每秒帧数 × 帧长。MPEG-1 每帧 1152 个采样，MPEG-2/2.5 是 576。 */
    private static double frameBytesPerSecond(Mp3FrameSync.Frame frame) {
        if (frame.sampleRate() <= 0 || frame.frameLength() <= 0) {
            return 0.0;
        }
        int samplesPerFrame = frame.version() == 3 ? 1152 : 576;
        return frame.sampleRate() / (double) samplesPerFrame * frame.frameLength();
    }

    private static boolean isNetEaseHost(URL url) {
        String host = url.getHost();
        if (host == null) {
            return false;
        }
        String lower = host.toLowerCase(Locale.ROOT);
        for (String suffix : NET_EASE_HOST_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 这次不接管，把流交给上游的"从头读 + 解码丢弃"。
     *
     * <p>每一条放弃路径都要留一句话，否则下次"快进还是没声音"就只能靠猜
     * —— 这类问题发生在网络/CDN/文件格式层面，光看现象区分不出来。
     * 用 {@code DEBUG} 打，正常游玩时不会刷屏。
     *
     * @return 恒为 {@code null}，方便写成 {@code return skip(url, "...");}
     */
    private static AudioInputStream skip(URL url, String reason) {
        LOGGER.debug("MP4 快进：这次不接管（{}），退回上游的从头追赶：{}", reason, url);
        return null;
    }

    private static void closeQuietly(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException ignored) {
            // 关不掉就算了，别影响播放。
        }
    }
}
