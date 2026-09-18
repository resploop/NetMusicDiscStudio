package com.netmusic.discstudio.client.netease;

import com.github.tartaricacid.netmusic.NetMusic;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.netmusic.discstudio.DiscStudio;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 网易云登录态在客户端的唯一持有者。
 * <p>
 * <b>为什么必须有这个类：</b>NetMusic 播放网易云音频时，客户端
 * {@code NetEaseHttpHandler} 会把 {@code NetMusic.NET_EASE_WEB_API} 里的请求头
 * 原样加到每一条音频请求上。而那个 WebApi 是 {@code new NetEaseMusic().getApi()} 建出来的，
 * 先天不带 Cookie——所以 VIP 歌曲即便写得出直链，也拿不到真正的音频流。
 * 把登录后的 {@code MUSIC_U} 塞进它的请求头，整条播放链路一行上游代码都不用改就通了。
 * <p>
 * 登录态只落在客户端本地（{@code config/netmusic_disc_studio-netease.json}），
 * 不随存档走，也不会发给服务端。单人游戏的集成服务端与客户端同进程，
 * 因此服务端那几个复用同一个 WebApi 的接口（专辑解析等）也会顺带带上 Cookie。
 */
public final class NetEaseSession {
    /** 会话文件名；放在 config 目录下与 NetMusic 自己的配置并列。 */
    private static final String FILE_NAME = "netmusic_disc_studio-netease.json";

    private static final Gson GSON = new Gson();

    private static String cookie = "";
    private static String nickname = "";
    private static boolean loaded;

    private NetEaseSession() {
    }

    /** 是否已经拿到可用的登录态。 */
    public static boolean loggedIn() {
        ensureLoaded();
        return !cookie.isBlank();
    }

    public static String cookie() {
        ensureLoaded();
        return cookie;
    }

    /** 账号昵称，仅用于界面回显；取不到时是空串。 */
    public static String nickname() {
        ensureLoaded();
        return nickname;
    }

    /**
     * 写入登录态并落盘，随后立刻同步给 NetMusic 的 WebApi。
     *
     * @param newCookie 网易云返回的整条 Cookie（至少要含 {@code MUSIC_U}）
     * @param newNickname 昵称，未知时传空串
     */
    public static void save(String newCookie, String newNickname) {
        loaded = true;
        cookie = newCookie == null ? "" : newCookie.trim();
        nickname = newNickname == null ? "" : newNickname;
        writeToDisk();
        apply();
    }

    /** 退出登录：清掉内存与磁盘上的登录态，并把请求头里的 Cookie 撤掉。 */
    public static void clear() {
        loaded = true;
        cookie = "";
        nickname = "";
        writeToDisk();
        apply();
    }

    /**
     * 把当前 Cookie 同步到 NetMusic 的 WebApi 请求头。
     * <p>
     * 这个方法要能在客户端刚启动、以及每次登录/退出时被安全地重复调用，
     * 因此对上游静态字段尚未初始化的情况也要兜住。
     */
    public static void apply() {
        ensureLoaded();
        if (NetMusic.NET_EASE_WEB_API == null) {
            return;
        }
        var headers = NetMusic.NET_EASE_WEB_API.getRequestPropertyData();
        if (cookie.isBlank()) {
            headers.remove("Cookie");
        } else {
            headers.put("Cookie", cookie);
        }
    }

    // ─────────────────────────── 落盘 ───────────────────────────

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path path = sessionFile();
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            cookie = readString(root, "cookie");
            nickname = readString(root, "nickname");
        } catch (Exception exception) {
            DiscStudio.LOGGER.warn("读取网易云登录态失败，按未登录处理: {}", path, exception);
            cookie = "";
            nickname = "";
        }
    }

    private static void writeToDisk() {
        Path path = sessionFile();
        try {
            Files.createDirectories(path.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("cookie", cookie);
            root.addProperty("nickname", nickname);
            Files.writeString(path, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            DiscStudio.LOGGER.warn("保存网易云登录态失败: {}", path, exception);
        }
    }

    private static Path sessionFile() {
        return FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
    }

    private static String readString(JsonObject root, String key) {
        var value = root.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }
}
