package com.netmusic.discstudio.client.netease;

import com.github.tartaricacid.netmusic.api.EncryptUtils;
import com.github.tartaricacid.netmusic.api.NetEaseMusic;
import com.github.tartaricacid.netmusic.api.NetWorker;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * 网易云扫码登录的三个接口调用。
 * <p>
 * 复用 NetMusic 已经有的两样东西，不重复造：
 * <ul>
 *   <li>{@link EncryptUtils#encryptedParam} —— weapi 的 AES 双层加密 + RSA；
 *       上游原本只用它请求专辑与歌单，登录接口的加密方式完全相同，直接拿来用。</li>
 *   <li>{@link NetWorker#HTTP_CLIENT} —— 带用户代理配置的 HttpClient。</li>
 * </ul>
 * 所有方法都会阻塞，必须在工作线程调用，不要在界面线程直接调。
 * <p>
 * <b>为什么二维码流程单独用一个带 CookieJar 的 HttpClient：</b>网易云在
 * {@code unikey} 响应里会顺手下发 {@code NMTID} 之类的会话 Cookie，官方网页是带着它去轮询的。
 * {@link NetWorker#HTTP_CLIENT} 不带 CookieJar，轮询请求等于"零 Cookie 裸奔"，
 * 很容易被风控盯上。这里换一个挂了 {@link CookieManager} 的客户端，让两跳共享同一份 Cookie。
 * <p>
 * <b>关于 8821：</b>实测（并与 ncmctl / HyPlayer 等第三方实现的说法一致）网易云现在对
 * 扫码登录的风控非常严，扫完确认后服务端可能直接回 {@code 8821 = 请切换其他登录方式或升级新版本再试}，
 * 需要行为验证码，客户端基本绕不过去。因此这一路只当"锦上添花"，真正的可用路径是
 * {@link #verifyCookie} 那条 Cookie 登录——它走的是普通接口，目前稳定可用。
 */
public final class NetEaseLoginApi {

    /** 申请二维码用的 key，不需要加密。 */
    private static final String UNIKEY_URL = "https://music.163.com/api/login/qrcode/unikey?type=1";

    /** 用 key 轮询扫码结果，需要 weapi 加密。 */
    private static final String POLL_URL = "https://music.163.com/weapi/login/qrcode/client/login?csrf_token=";

    /** 未加密的轮询兜底；{@code weapi} 返回空体时改走它（实测能正常回 800/801/802/803）。 */
    private static final String PLAIN_POLL_URL = "https://music.163.com/api/login/qrcode/client/login";

    /** 二维码里真正要编码的内容；手机 App 扫到它就会跳到确认页。 */
    private static final String QR_CONTENT_PREFIX = "https://music.163.com/login?codekey=";

    /** 拿当前账号信息，用来校验手动粘贴的 Cookie 是否有效并取出昵称。 */
    private static final String ACCOUNT_URL = "https://music.163.com/api/nuser/account/get";

    /**
     * 登录流程专用的 HttpClient：自带 CookieJar，其余（代理、超时、UA）与 NetMusic 保持一致。
     * 代理每次都现读配置，改完 NetMusic 的代理设置不用重启。
     */
    private static final HttpClient LOGIN_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .proxy(new ProxySelector() {
                @Override
                public List<Proxy> select(URI uri) {
                    return List.of(NetWorker.getProxyFromConfig());
                }

                @Override
                public void connectFailed(URI uri, SocketAddress address, IOException failure) {
                    // 与 NetWorker 一样静默处理，失败信息由调用方的异常体现。
                }
            })
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private NetEaseLoginApi() {
    }

    /** 申请一个新二维码并返回其内容，直接交给 {@code QrCode} 编码即可。 */
    public static String requestQrContent() throws Exception {
        HttpResponse<String> response = LOGIN_CLIENT.send(
                base(URI.create(UNIKEY_URL))
                        .POST(HttpRequest.BodyPublishers.ofString("type=1", StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String body = response.body();
        String unikey = readString(parse(body), "unikey");
        if (unikey.isBlank()) {
            throw new IllegalStateException("网易云没有返回二维码 key，响应: " + shorten(body));
        }
        return QR_CONTENT_PREFIX + unikey;
    }

    /**
     * 轮询一次扫码状态。
     * <p>
     * 先按官方网页的方式发 {@code weapi}（加密参数）；万一拿回空响应体（网易云偶发），
     * 再退到未加密的 {@code /api/} 变体，避免一次抽风就把整个扫码流程判死。
     *
     * @param qrContent {@link #requestQrContent()} 的返回值
     */
    public static PollResult poll(String qrContent) throws Exception {
        String unikey = qrContent.substring(qrContent.indexOf("codekey=") + "codekey=".length());
        String param = EncryptUtils.encryptedParam(
                "{\"key\":\"" + unikey + "\",\"type\":1}");
        HttpResponse<String> response = LOGIN_CLIENT.send(
                base(URI.create(POLL_URL))
                        .POST(HttpRequest.BodyPublishers.ofString(param, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        String body = response.body();
        if (body == null || body.isBlank()) {
            // 加密那一跳没给出东西，换明文接口再问一次；这次连 key 都放进 query 里。
            HttpResponse<String> plain = LOGIN_CLIENT.send(
                    base(URI.create(PLAIN_POLL_URL + "?key=" + unikey + "&type=1"))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            body = plain.body();
            response = plain;
        }
        JsonObject root = parse(body);
        int code = root.has("code") ? root.get("code").getAsInt() : -1;

        String cookie = readString(root, "cookie");
        if (cookie.isBlank()) {
            // 个别版本把登录票放在响应头里，兜一手。
            cookie = extractMusicU(response.headers().allValues("Set-Cookie"));
        }
        String nickname = "";
        if (root.has("profile") && root.getAsJsonObject("profile").isJsonObject()) {
            nickname = readString(root.getAsJsonObject("profile"), "nickname");
        }
        return new PollResult(code, cookie, nickname, readString(root, "message"));
    }

    /**
     * 用一条现成的 Cookie 校验登录态并取昵称。
     * <p>
     * 手动粘贴的路径靠它判断"这串 Cookie 到底能不能用"，避免把废票写进会话文件后
     * 让人以为登录成功了。粘进来的东西会先过一遍 {@link #normalizeCookie}。
     *
     * @return 昵称；Cookie 无效时返回 {@code null}
     */
    public static String verifyCookie(String cookie) throws Exception {
        HttpResponse<String> response = NetWorker.send(
                base(URI.create(ACCOUNT_URL)).header("Cookie", normalizeCookie(cookie)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonObject root = parse(response.body());
        if (root.has("profile") && root.getAsJsonObject("profile").isJsonObject()) {
            return readString(root.getAsJsonObject("profile"), "nickname");
        }
        return null;
    }

    /**
     * 把玩家粘进来的东西整理成合法的 Cookie 头。
     * <p>
     * 允许三种写法，都能用：
     * <ul>
     *   <li>整条 Cookie —— {@code __csrf=xx; MUSIC_U=yy; ...}（浏览器里全选复制就是这种）</li>
     *   <li>单独一项 —— {@code MUSIC_U=yy}</li>
     *   <li>只复制了值 —— 一长串没有等号的 token，自动补上 {@code MUSIC_U=}</li>
     * </ul>
     * 最后一种最容易发生：在浏览器的 Cookies 面板里点一下就能只取值。
     * <b>存进会话前必须过这个方法</b>——否则裸值会被当成 Cookie 头直接发出去，播放时认证不过。
     */
    public static String normalizeCookie(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || value.contains("=")) {
            return value;
        }
        return "MUSIC_U=" + value;
    }

    // ─────────────────────────── 内部工具 ───────────────────────────

    private static String post(String url, String form) throws Exception {
        HttpResponse<String> response = NetWorker.send(
                base(URI.create(url))
                        .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return response.body();
    }

    /** 网易云要求带 Origin/Referer/UA，换个这几个头就容易拿到 400 或空响应。 */
    private static HttpRequest.Builder base(URI uri) {
        return HttpRequest.newBuilder(uri)
                .header("Origin", "https://music.163.com")
                .header("Referer", "https://music.163.com/")
                .header("User-Agent", NetEaseMusic.getUserAgent())
                .header("Content-Type", "application/x-www-form-urlencoded");
    }

    private static JsonObject parse(String body) {
        // 网易云偶尔会回一个空的 200，直接把空串丢给 Gson 会抛 JsonSyntaxException，
        // 让调用方误以为是网络故障。这里统一当"没有字段"处理，由 code=-1 表达。
        if (body == null || body.isBlank()) {
            return new JsonObject();
        }
        return JsonParser.parseString(body).getAsJsonObject();
    }

    /** 从 {@code Set-Cookie} 里挑出 MUSIC_U 那一项。 */
    private static String extractMusicU(List<String> setCookies) {
        for (String raw : setCookies) {
            for (String part : raw.split(";")) {
                String trimmed = part.trim();
                if (trimmed.startsWith("MUSIC_U=")) {
                    return trimmed;
                }
            }
        }
        return "";
    }

    private static String readString(JsonObject root, String key) {
        var value = root.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static String shorten(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }

    /**
     * 一次轮询的结果。
     *
     * @param code     网易云的状态码：800 过期、801 待扫、802 待确认、803 成功、
     *                 8821 风控拦截（需行为验证码，客户端绕不过）
     * @param cookie   登录成功时的整条 Cookie
     * @param nickname 登录成功时的昵称
     * @param message  服务端给的说明，仅用于排错
     */
    public record PollResult(int code, String cookie, String nickname, String message) {
        /** 网易云风控固定的拦截码：{@code 8821 = 请切换其他登录方式或升级新版本再试}。 */
        public static final int CODE_RISK_CONTROL = 8821;

        public boolean waiting() {
            return code == 801;
        }

        public boolean scanned() {
            return code == 802;
        }

        public boolean success() {
            return code == 803 && !cookie.isBlank();
        }

        public boolean expired() {
            return code == 800;
        }

        /**
         * 是否被网易云风控挡下来了。
         * <p>
         * 这不是本模组能修的东西：服务端要求行为验证码，客户端再怎么重试也是同一个结果。
         * 界面遇到它应当停止轮询并引导玩家改用 Cookie 登录，而不是每 2 秒撞一次墙。
         */
        public boolean riskControlled() {
            return code == CODE_RISK_CONTROL;
        }
    }
}
