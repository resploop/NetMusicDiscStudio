package com.netmusic.discstudio.client.cover;

import com.github.tartaricacid.netmusic.api.NetWorker;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import com.netmusic.discstudio.DiscStudio;
import com.netmusic.discstudio.disc.AlbumPlaylist;
import com.netmusic.discstudio.disc.NetworkDiscs;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 专辑封面 → 运行时贴图的缓存。
 * <p>
 * 流程：拿到封面地址 → 后台线程下载并解码 → 把封面加工成光盘图并注册成
 * {@link DynamicTexture}。注册用的 {@link Identifier} 由地址哈希得来，同一张专辑只会下载一次。
 * <p>
 * <b>网络请求刻意复用 NetMusic 自己的 {@link NetWorker#HTTP_CLIENT}</b>，而不是新建一个
 * {@code HttpClient}：上游那个客户端带了 {@code ConfigProxySelector}（走玩家在 NetMusic
 * 配置里填的代理）、强制 HTTP/1.1 且 {@code Redirect.ALWAYS}。刻录专辑时用的就是它，
 * 所以这是"已经被验证能连通网易云"的那条网络路径；自己另开一个直连客户端在需要代理的
 * 网络环境里会静默失败。
 * <p>
 * 贴图要等下载完成才可用，所以 {@link #textureFor(String)} 在准备好之前返回 {@code null}；
 * 调用方拿到 {@code null} 时用未刻录空白盘那张彩虹光盘贴图兜底，等贴图就绪后物品模型的
 * 渲染状态会因为参数变化而自动重建。
 */
public final class AlbumCoverTextures {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 缓存上限。每张 128×128 RGBA 约占 64 KB，128 张约 8 MB。 */
    private static final int MAX_ENTRIES = 128;

    /** 单个地址最多尝试几次。网易云图片 CDN 偶尔抽风，重试比直接放弃划算。 */
    private static final int MAX_ATTEMPTS = 3;
    /** 两次尝试之间的间隔。 */
    private static final long RETRY_DELAY_MILLIS = 15_000L;

    /**
     * 与 NetMusic 请求网易云 API 时用的浏览器 UA 保持一致。
     * <p>
     * 图片 CDN 对非浏览器 UA 的请求并不总是友好，之前自造的 UA 是拿不到图的嫌疑之一。
     */
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 6.1; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/81.0.4044.138 Safari/537.36";

    private static final Map<String, State> STATES = new ConcurrentHashMap<>();

    private AlbumCoverTextures() {
    }

    /** 单个封面地址的状态。字段会被 HTTP 线程写、被渲染线程读，因此都是 volatile。 */
    private static final class State {
        private volatile int attempts;
        private volatile long nextAttemptAt;
        private volatile @Nullable Identifier texture;
        private volatile @Nullable String lastError;
        /** 重试用尽后置位，表示这个地址已经确定拿不到封面了。 */
        private volatile boolean exhausted;
    }

    /**
     * 从唱片物品上取封面贴图。
     *
     * @return 贴图就绪时返回其 {@link Identifier}；未就绪、下载失败或没有封面时返回 {@code null}
     */
    @Nullable
    public static Identifier textureForDisc(ItemStack stack) {
        AlbumPlaylist playlist = NetworkDiscs.playlist(stack);
        if (playlist == null) {
            return null;
        }
        return textureFor(playlist.coverUrl());
    }

    @Nullable
    public static Identifier textureFor(@Nullable String url) {
        if (url == null || url.isBlank()) {
            return null;
        }

        State state = STATES.computeIfAbsent(url, key -> new State());
        Identifier ready = state.texture;
        if (ready != null) {
            return ready;
        }
        if (state.exhausted) {
            return null;
        }
        if (STATES.size() > MAX_ENTRIES) {
            LOGGER.warn("封面缓存已满（{} 项），不再下载新封面: {}", STATES.size(), url);
            state.exhausted = true;
            state.lastError = "缓存已满";
            return null;
        }

        long now = System.currentTimeMillis();
        if (now < state.nextAttemptAt) {
            return null;
        }
        state.attempts++;
        state.nextAttemptAt = now + RETRY_DELAY_MILLIS;
        download(url, state);
        return null;
    }

    /** 拿不到封面的原因，供界面提示；没有问题或还没结论时返回空串。 */
    public static String failureReason(@Nullable String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        State state = STATES.get(url);
        if (state == null || !state.exhausted) {
            return "";
        }
        String reason = state.lastError;
        return reason == null ? "未知原因" : reason;
    }

    /**
     * 往 tooltip 里补一行封面状态。
     * <p>
     * 只在"确实有问题"时输出：没有封面地址，或者重试用尽仍然拿不到图。
     * 加载中不打扰玩家，但失败时必须说清楚是哪一环的问题——因为兜底贴图与未刻录的空白盘
     * 是同一张彩虹光盘，光看外观完全分不出"封面没拿到"和"这张碟还没刻录"。
     */
    public static void appendCoverHint(Consumer<Component> tooltip, @Nullable String url) {
        if (url == null || url.isBlank()) {
            tooltip.accept(Component.translatable("tooltip.netmusic_disc_studio.album.no_cover")
                    .withStyle(ChatFormatting.YELLOW));
            return;
        }
        String reason = failureReason(url);
        if (!reason.isEmpty()) {
            tooltip.accept(Component.translatable("tooltip.netmusic_disc_studio.album.cover_failed", reason)
                    .withStyle(ChatFormatting.YELLOW));
        }
    }

    private static void download(String url, State state) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException exception) {
            finishWithError(state, url, "地址不合法", exception);
            return;
        }

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", USER_AGENT)
                    .header("Origin", "http://music.163.com")
                    .header("Referer", "http://music.163.com/")
                    .GET()
                    .build();
        } catch (IllegalArgumentException exception) {
            finishWithError(state, url, "请求头被拒绝", exception);
            return;
        }

        LOGGER.info("封面 开始下载（第 {} 次尝试）: {}", state.attempts, url);
        // 用上游的 client：同一个代理配置、同样的 HTTP/1.1 与重定向策略。
        NetWorker.HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> decode(url, response))
                .whenComplete((cover, error) -> {
                    if (error != null || cover == null) {
                        Throwable cause = error instanceof CompletionException && error.getCause() != null
                                ? error.getCause()
                                : error;
                        finishWithError(state, url, describe(cause), cause);
                        return;
                    }
                    // 贴图创建与注册要碰 GPU，必须回到渲染线程。
                    Minecraft.getInstance().execute(() -> register(url, state, cover));
                });
    }

    /** 把异常转成一句能给玩家看的短原因。 */
    private static String describe(@Nullable Throwable cause) {
        if (cause == null) {
            return "未知原因";
        }
        String message = cause.getMessage();
        if (cause instanceof IOException && message != null && !message.isBlank()) {
            // 网络与解码错误的消息本身就是最清楚的描述，不必再冠上类名。
            return message;
        }
        String simpleName = cause.getClass().getSimpleName();
        return message == null || message.isBlank() ? simpleName : simpleName + ": " + message;
    }

    /** 解码后的封面像素。ARGB 顺序，与 {@code NativeImage.getPixel} 一致。 */
    private record Cover(int width, int height, int[] argb) {
    }

    /**
     * 把响应体解成像素。
     * <p>
     * <b>不能用 {@code NativeImage.read}</b>：MC 26.1 的那个方法在交给 STB 之前会先跑
     * {@code PngInfo.validateHeader}，也就是<b>只接受 PNG</b>，而网易云封面是 JPEG，
     * 一律以 {@code IOException("Bad PNG Signature")} 失败。这里改用 JDK 自带的
     * {@code ImageIO}，JPEG / PNG / GIF / BMP 都能解。
     */
    private static Cover decode(String url, HttpResponse<byte[]> response) {
        if (response.statusCode() / 100 != 2) {
            throw new CompletionException(new IOException("HTTP " + response.statusCode()));
        }
        byte[] body = response.body();
        if (body == null || body.length == 0) {
            throw new CompletionException(new IOException("响应体为空"));
        }

        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(body));
        } catch (IOException | RuntimeException exception) {
            throw new CompletionException(new IOException("图片解码失败（" + describeBody(response) + "）", exception));
        }
        if (image == null) {
            // ImageIO 认不出格式时返回 null 而不是抛异常。
            throw new CompletionException(new IOException("不是可识别的图片格式（" + describeBody(response) + "）"));
        }

        int width = image.getWidth();
        int height = image.getHeight();
        // getRGB 会把任意色彩模型转成 sRGB 的 ARGB，因此不需要再建一张 TYPE_INT_ARGB 的副本。
        int[] argb = image.getRGB(0, 0, width, height, null, 0, width);
        LOGGER.info("封面 已解码: {} ×{}（{}）", width, height, describeBody(response));
        return new Cover(width, height, argb);
    }

    /** 描述响应体：大小、Content-Type、开头几个字节。格式不对时一眼能看出拿到的是不是图片。 */
    private static String describeBody(HttpResponse<byte[]> response) {
        byte[] body = response.body();
        int length = body == null ? 0 : body.length;
        StringBuilder hex = new StringBuilder();
        int shown = Math.min(8, length);
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                hex.append(' ');
            }
            hex.append(String.format(Locale.ROOT, "%02X", body[i]));
        }
        return length + " 字节, Content-Type=" + response.headers().firstValue("Content-Type").orElse("?")
                + ", 头部=" + hex;
    }

    private static void register(String url, State state, Cover cover) {
        NativeImage disc = null;
        try {
            disc = DiscImageFactory.discFrom(cover.width(), cover.height(), cover.argb());
            Identifier id = Identifier.fromNamespaceAndPath(DiscStudio.MOD_ID,
                    "album_cover/" + Integer.toUnsignedString(url.hashCode(), 16));
            Minecraft.getInstance().getTextureManager().register(id,
                    new DynamicTexture(() -> "NetMusic Disc Studio album cover", disc));
            // DynamicTexture 接手了 disc 的所有权，这里不能再关。
            disc = null;
            state.texture = id;
            LOGGER.info("封面 已生成贴图: {} -> {}", url, id);
        } catch (RuntimeException exception) {
            finishWithError(state, url, "生成贴图失败: " + describe(exception), exception);
        } finally {
            if (disc != null) {
                disc.close();
            }
        }
    }

    private static void finishWithError(State state, String url, String reason, @Nullable Throwable cause) {
        state.lastError = reason;
        boolean exhausted = state.attempts >= MAX_ATTEMPTS;
        state.exhausted = exhausted;
        if (exhausted) {
            LOGGER.warn("封面 放弃（已尝试 {} 次）: {} —— {}", state.attempts, url, reason, cause);
        } else {
            LOGGER.warn("封面 本次失败，稍后重试: {} —— {}", url, reason);
        }
    }
}
