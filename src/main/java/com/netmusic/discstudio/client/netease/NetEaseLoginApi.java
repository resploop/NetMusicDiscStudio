package com.netmusic.discstudio.client.netease;

import com.github.tartaricacid.netmusic.api.EncryptUtils;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 网易云扫码登录的三个接口调用。
 * <p>
 * 复用 NetMusic 已经有的东西，不重复造：
 * <ul>
 *   <li>{@link EncryptUtils#encryptedParam} —— weapi 的 AES 双层加密 + RSA；
 *       明文轮询失手时用它兜底。上游原本只用它请求专辑与歌单，加密方式完全相同。</li>
 *   <li>{@link NetWorker#HTTP_CLIENT} 的代理配置 —— 见下方 {@link #LOGIN_CLIENT}。</li>
 * </ul>
 * 所有方法都会阻塞，必须在工作线程调用，不要在界面线程直接调。
 * <p>
 * <b>为什么二维码流程单独用一个带 CookieJar 的 HttpClient：</b>网易云在
 * {@code unikey} 响应里会顺手下发 {@code NMTID} 之类的会话 Cookie，官方客户端是带着它去轮询的。
 * {@link NetWorker#HTTP_CLIENT} 不带 CookieJar，轮询请求等于"零 Cookie 裸奔"。
 * 这里换一个挂了 {@link CookieManager} 的客户端，让两跳共享同一份 Cookie。
 * <p>
 * <b>⭐ 走哪条通道决定了会不会被风控（2026-09-19 修正）：</b>
 * <ul>
 *   <li>{@code type=1} + 浏览器 UA = <b>网页扫码</b>。手机点确认后服务端回
 *       {@code 8821 = 请切换其他登录方式或升级新版本再试}，要求行为验证码，这条路走不通。</li>
 *   <li>{@code type=3} + 官方桌面版 UA = <b>PC 客户端通道</b>，不触发风控。这是现在用的。</li>
 * </ul>
 * 参考实现：<a href="https://github.com/ming-sc/NetMusic-BetterLogin">ming-sc/NetMusic-BetterLogin</a>
 * （用户实机验证可用）。两个通道只差 {@code type} 与 UA 这一对参数。
 * <p>
 * <b>当初为什么判断错了：</b>做过一次"四种 UA × 加密/明文端点"的对照实验，结论是"全都是 801，
 * 所以请求头不影响"。但那实验是在<b>扫码之前</b>做的，而 8821 出现在<b>手机点确认之后</b>——
 * 唯一真正起作用的变量 {@code type} 恰恰没被纳入对照。教训：<b>结论要写清测试覆盖到哪一步</b>，
 * 没被测到的阶段不能推断"无影响"。
 * <p>
 * <b>别再优化的方向：</b>CookieJar、请求头、加密/明文端点都已被排除，换它们没有意义；
 * 真正的开关只有 {@code type} + UA 这一对。若哪天 {@code type=3} 也被拦，
 * 下一个可试的杠杆是给轮询请求也带上 {@code os=pc; appver=3.1.6}（见 {@link #DESKTOP_COOKIE_FIELDS}）。
 */
public final class NetEaseLoginApi {

    /** 扫码登录通道：{@code 3} = PC 客户端。网页通道是 {@code 1}，会被风控回 8821，别再改回去。 */
    private static final int QR_TYPE = 3;

    /**
     * 冒充官方 PC 客户端。与 {@link #QR_TYPE} 是<b>配套的一对</b>，缺一不可 ——
     * 服务端要同时看到这两样，才会把请求当成桌面客户端登录，而不是"网页扫码"。
     * <p>
     * 字符串与参考实现逐字一致，不要"顺手美化"，也不要换成 NetMusic 的
     * {@code getUserAgent()}（那是浏览器 UA，等于回到网页通道）。
     */
    private static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36"
            + " (KHTML, like Gecko) Safari/537.36 Chrome/91.0.4472.164 NeteaseMusicDesktop/3.1.6";

    /** 申请二维码用的 key，不需要加密。 */
    private static final String UNIKEY_URL = "https://music.163.com/api/login/qrcode/unikey?type=" + QR_TYPE;

    /** 轮询扫码结果。桌面客户端走的是未加密的 {@code /api/} 变体，这也是首选。 */
    private static final String PLAIN_POLL_URL = "https://music.163.com/api/login/qrcode/client/login";

    /** {@code weapi} 加密轮询，仅在明文那一跳拿回空响应体时兜底。 */
    private static final String POLL_URL = "https://music.163.com/weapi/login/qrcode/client/login?csrf_token=";

    /** 二维码里真正要编码的内容；手机 App 扫到它就会跳到确认页。 */
    private static final String QR_CONTENT_PREFIX = "https://music.163.com/login?codekey=";

    /** 拿当前账号信息，用来校验手动粘贴的 Cookie 是否有效并取出昵称。 */
    private static final String ACCOUNT_URL = "https://music.163.com/api/nuser/account/get";

    /**
     * 登录成功后补进 Cookie 的两个字段，与 {@link #DESKTOP_UA} 配套。
     * <p>
     * 它们不影响登录本身（登录都完成了才加），但会跟着 Cookie 一起进入 <b>播放</b>链路的请求头，
     * 让取直链的请求也表现为桌面客户端 —— 官方桌面版就是这么带的。
     * <p>
     * 安全性已核对：netmusic 的 {@link EncryptUtils} <b>不读</b>这两个 cookie
     * （javap 全类搜 {@code os}/{@code appver} 无命中），所以不会干扰 weapi 加密。
     */
    private static final String DESKTOP_COOKIE_FIELDS = "os=pc; appver=3.1.6";

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
                base(URI.create(UNIKEY_URL)).GET().build(),
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
     * 首选未加密的 {@code /api/login/qrcode/client/login?key=..&type=3}（桌面客户端就是这么问的）；
     * 只有它拿回空白或不可解析的响应体时，才退到 {@code weapi} 加密那一跳再问一次，
     * 避免一次抽风就把整个扫码流程判死。
     *
     * @param qrContent {@link #requestQrContent()} 的返回值
     */
    public static PollResult poll(String qrContent) throws Exception {
        String unikey = qrContent.substring(qrContent.indexOf("codekey=") + "codekey=".length());
        HttpResponse<String> response = LOGIN_CLIENT.send(
                base(URI.create(PLAIN_POLL_URL + "?key=" + unikey + "&type=" + QR_TYPE))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String body = response.body();
        if (body == null || !body.trim().startsWith("{")) {
            HttpResponse<String> encrypted = LOGIN_CLIENT.send(
                    base(URI.create(POLL_URL))
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    EncryptUtils.encryptedParam(
                                            "{\"key\":\"" + unikey + "\",\"type\":" + QR_TYPE + "}"),
                                    StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (encrypted.body() != null && encrypted.body().trim().startsWith("{")) {
                response = encrypted;
                body = encrypted.body();
            }
        }
        JsonObject root = parse(body);
        int code = root.has("code") ? root.get("code").getAsInt() : -1;

        String cookie = readString(root, "cookie");
        if (cookie.isBlank()) {
            // 个别版本把登录票放在响应头里，兜一手。
            cookie = extractMusicU(response.headers().allValues("Set-Cookie"));
        }
        if (code == 803 && !cookie.isBlank()) {
            cookie = withDesktopFields(cookie);
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

    /** 网易云要求带 Origin/Referer/UA，换个这几个头就容易拿到 400 或空响应。 */
    private static HttpRequest.Builder base(URI uri) {
        return HttpRequest.newBuilder(uri)
                .header("Origin", "https://music.163.com")
                .header("Referer", "https://music.163.com/")
                .header("User-Agent", DESKTOP_UA)
                .header("Content-Type", "application/x-www-form-urlencoded");
    }

    /**
     * 给登录成功的 Cookie 补上桌面客户端的身份字段（见 {@link #DESKTOP_COOKIE_FIELDS}）。
     * <p>
     * 按 <b>cookie 名</b>比对而不是拿整串做子串匹配：{@code MUSIC_U} 的值是一长串 base64，
     * 子串匹配有概率被值里的字符骗到，那样就会漏加字段。
     */
    private static String withDesktopFields(String cookie) {
        String value = cookie == null ? "" : cookie.trim();
        if (value.isEmpty()) {
            return value;
        }
        Set<String> present = new HashSet<>();
        for (String part : value.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                present.add(part.substring(0, eq).trim().toLowerCase(Locale.ROOT));
            }
        }
        StringBuilder result = new StringBuilder(value);
        for (String field : DESKTOP_COOKIE_FIELDS.split(";")) {
            String item = field.trim();
            if (!present.contains(item.split("=", 2)[0].toLowerCase(Locale.ROOT))) {
                result.append("; ").append(item);
            }
        }
        return result.toString();
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
     *                 8821 风控拦截（需行为验证码）
     * @param cookie   登录成功时的整条 Cookie，已补上桌面客户端字段
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
         * 走 {@code type=3} 桌面客户端通道后基本不会出现；真出现的话说明风控又收紧了，
         * 界面应当停止轮询并引导玩家改用 Cookie 登录，而不是每 2 秒撞一次墙。
         */
        public boolean riskControlled() {
            return code == CODE_RISK_CONTROL;
        }
    }
}
