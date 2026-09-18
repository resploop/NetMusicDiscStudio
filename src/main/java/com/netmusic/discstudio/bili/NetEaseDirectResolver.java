package com.netmusic.discstudio.bili;

import com.github.tartaricacid.netmusic.NetMusic;
import com.github.tartaricacid.netmusic.api.NetWorker;
import com.github.tartaricacid.netmusic.api.resolver.IAsyncSongUrlResolver;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.netmusic.discstudio.DiscStudioConfig;
import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网易云直链解析器：把网抑云那串"语义 URL"换成真正能拉的 CDN 直链。
 * <p>
 * <b>为什么不能原样放行：</b>{@code https://music.163.com/song/media/outer/url?id=X.mp3}
 * 这个老接口对<b>收费曲（fee=1，也就是俗称的 VIP 歌）恒返回
 * {@code 302 -> http://music.163.com/404} 且 {@code Content-Length: 0}</b>，
 * 带不带登录 Cookie 都一样——客户端 {@code NetEaseHttpHandler} 拿到 0 字节流，
 * {@code AudioSystem.getAudioInputStream} 直接抛
 * {@code UnsupportedAudioFileException: Stream of unsupported format}，
 * 于是"cookie 登录成功了却还是放不出 VIP 歌"。
 * <p>
 * <b>正确来源是 {@code player/url} 接口：</b>实测同一首歌（id=740611）
 * {@code /api/song/enhance/player/url} 在<b>带 Cookie</b> 时返回真实 mp3 直链、
 * <b>不带 Cookie</b> 时 {@code url=null}——VIP 权限确实走的是 Cookie，只是走错了接口。
 * <p>
 * <b>流程：</b>取 {@code outer/url?id=X} 里的 X → 调 NetMusic 自己的
 * {@code weapi/song/enhance/player/url}（它会把我们注入的 Cookie 一起带上）→
 * 拿 {@code data[0].url} → 回填成新的 {@link ItemMusicCD.SongInfo}。
 * 唱片上仍然存原始的 {@code outer/url}（现代化唱片机的 {@code rawUrl} 没被改），
 * 因此每放一次都会重新解析一次，直链过期也不怕。
 * <p>
 * <b>两条请求路径：</b>先走上游加密的 {@code weapi} 变体；返回空再退到未加密的
 * {@code /api/} 变体（实测可用），两条都用同一个带 Cookie 的请求头表。
 * <p>
 * <b>关于 {@code vip} 标志：</b>解析结果必须把 {@code vip} 置为 {@code false}。
 * 否则现代化唱片机其它入口（MP4、平板）里那句
 * {@code songInfo.vip && !MusicPlayResolverManager.canResolve(songInfo)} 会二次判定：
 * 此时 {@code songUrl} 已经是 {@code m7xx.music.126.net} 直链、不再匹配 {@code outer/url}，
 * {@code canResolve} 返回 false，刚解析好的歌又被自己的 VIP 闸门拦住。
 * <p>
 * 服务器需要严格尊重网易云 VIP 限制时，把 {@code allowNetEaseDirectPlayback} 关掉即可，
 * 本解析器立刻退回"不接管"，VIP 曲目会被唱片机照常拒绝。
 */
public class NetEaseDirectResolver implements IAsyncSongUrlResolver {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String NETEASE_OUTER_URL = "https://music.163.com/song/media/outer/url";

    /** 从 {@code outer/url?id=123.mp3}（后缀可能是 {@code .mp3} 也可能没有）里抠出歌曲 ID。 */
    private static final Pattern OUTER_SONG_ID = Pattern.compile("[?&]id=(\\d+)");

    /**
     * 请求码率。320000 稳定返回 <b>mp3</b>（exhigh）；再往上（999000）会拿到 flac，
     * 而客户端 {@code MpegAudioFileReader} + {@code Mp3Util.skipID3} 这条链对 mp3 支持最好。
     */
    private static final long PREFERRED_BITRATE = 320_000L;

    /** 未加密的备用接口；{@code ids} 要写成 {@code [123]} 这种 JSON 数组字面量。 */
    private static final String PLAIN_PLAYER_URL = "https://music.163.com/api/song/enhance/player/url";

    private static final ExecutorService RESOLVE_EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "DiscStudio-NetEaseResolve");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public boolean canResolve(ItemMusicCD.SongInfo songInfo) {
        if (!DiscStudioConfig.allowNetEaseDirectPlayback()) {
            return false;
        }
        // 只认"还没解析过"的语义 URL；解析后的 mxxx.music.126.net 直链不再进这里，
        // 免得来回震荡着重新解析。
        return songInfo != null
                && songInfo.songUrl != null
                && songInfo.songUrl.startsWith(NETEASE_OUTER_URL);
    }

    @Override
    public CompletableFuture<ItemMusicCD.SongInfo> resolve(ItemMusicCD.SongInfo songInfo) {
        if (!canResolve(songInfo)) {
            return CompletableFuture.completedFuture(songInfo);
        }
        Matcher matcher = OUTER_SONG_ID.matcher(songInfo.songUrl);
        if (!matcher.find()) {
            LOGGER.warn("网易云直链里没找到歌曲 ID，按原样放行: {}", songInfo.songUrl);
            return CompletableFuture.completedFuture(songInfo);
        }
        long songId = Long.parseLong(matcher.group(1));
        // 解析要发网络请求，绝不能占着服务端主线程（上游会在服务端线程上 join 这个 future）。
        return CompletableFuture.supplyAsync(() -> resolveBlocking(songInfo, songId), RESOLVE_EXECUTOR);
    }

    /** 在工作线程里真正把直链换掉；拿不到就原样返回，让行为退回本次修复之前（只是会多一条警告日志）。 */
    private static ItemMusicCD.SongInfo resolveBlocking(ItemMusicCD.SongInfo songInfo, long songId) {
        String url = "";
        try {
            url = requestPlayerUrl(songId);
        } catch (Exception exception) {
            LOGGER.warn("网易云直链解析请求异常: id={}", songId, exception);
        }
        if (url.isBlank()) {
            LOGGER.warn("网易云没有给出可播放直链，回退原始地址（多半是未登录或版权受限）: id={} url={}",
                    songId, songInfo.songUrl);
            return songInfo;
        }

        ItemMusicCD.SongInfo resolved = songInfo.clone();
        resolved.songUrl = url;
        // 见类注释：不回填 vip=false 的话，MP4/平板那几处 VIP 闸门会把解析结果再次拦下。
        resolved.vip = false;
        LOGGER.debug("网易云直链解析成功: id={} -> {}", songId, url);
        return resolved;
    }

    /**
     * 依次尝试上游加密的 {@code weapi} 接口与未加密的 {@code api} 接口，返回第一个非空直链。
     * 两者都复用 {@link NetMusic#NET_EASE_WEB_API} 的请求头表，Cookie 是同一份。
     */
    private static String requestPlayerUrl(long songId) throws Exception {
        String url = readFirstUrl(NetMusic.NET_EASE_WEB_API.mp3(PREFERRED_BITRATE, songId));
        if (!url.isBlank()) {
            return url;
        }
        String plain = NetWorker.get(
                PLAIN_PLAYER_URL + "?ids=%5B" + songId + "%5D&br=" + PREFERRED_BITRATE,
                NetMusic.NET_EASE_WEB_API.getRequestPropertyData());
        return readFirstUrl(plain);
    }

    /** 从 {@code {"data":[{"url":"http://..."}],"code":200}} 里取第一条的 url；任何异常都当"没有"。 */
    private static String readFirstUrl(String json) {
        if (json == null || json.isBlank()) {
            return "";
        }
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonElement data = root.get("data");
            if (data == null || !data.isJsonArray()) {
                return "";
            }
            JsonArray array = data.getAsJsonArray();
            if (array.isEmpty() || !array.get(0).isJsonObject()) {
                return "";
            }
            JsonElement url = array.get(0).getAsJsonObject().get("url");
            return url == null || url.isJsonNull() ? "" : url.getAsString();
        } catch (Exception exception) {
            LOGGER.warn("解析网易云直链响应失败: {}", shorten(json), exception);
            return "";
        }
    }

    private static String shorten(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }

    @Override
    public int getPriority() {
        // 低于 BiliAudioResolver(50)，避免影响 B 站选集。
        return 40;
    }
}
