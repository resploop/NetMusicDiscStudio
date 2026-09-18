package com.netmusic.discstudio.client.screen;

import com.netmusic.discstudio.DiscStudio;
import com.netmusic.discstudio.client.netease.NetEaseLoginApi;
import com.netmusic.discstudio.client.netease.NetEaseSession;
import com.netmusic.discstudio.client.qr.QrCode;
import com.zhongbai233.net_music_can_play_bili.gui.BlackGoldButton;
import com.zhongbai233.net_music_can_play_bili.gui.BlackGoldScreen;
import com.zhongbai233.net_music_can_play_bili.gui.BlackGoldUi;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 网易云账号登录界面：<b>以粘贴 Cookie 为主，扫码是备选</b>。
 * <p>
 * 登录态本身由 {@link NetEaseSession} 保存并注入 NetMusic 的 WebApi，本界面只负责
 * "拿到一条可用的 Cookie"这一件事。
 * <p>
 * <b>为什么把 Cookie 放主位：</b>实测（以及与 ncmctl 等仍在维护的第三方实现的说法一致）
 * 网易云对扫码登录的风控非常严：手机确认之后服务端会回 {@code 8821 需要行为验证码}，
 * 客户端无解。更关键的是，这个拦截<b>不取决于我们的请求怎么写</b>——加密的 weapi 与
 * 未加密的 /api 两个轮询端点、新旧两套 User-Agent，在未扫码时都稳定返回 {@code 801 等待扫码}，
 * 拦截只发生在手机点确认的那一刻。所以扫码这条路保留，但不再作为默认入口：
 * 不点「扫码登录」就<b>一个请求都不发</b>，免得白白给账号招风控。
 * <p>
 * 界面继承 NetMusicCanPlayBili 的黑金主题，与唱片机、换 BV 那几个界面保持一致的观感。
 * 它不绑定任何方块实体，所以 {@code blockPos} 传 {@link BlockPos#ZERO}。
 * <p>
 * <b>自适应高度：</b>基类 {@link BlackGoldScreen} 的面板高度是写死的常量，而本界面内容不少，
 * 316 高在 1080p / GUI 缩放 4（可视区只有 270 高）下会顶出屏幕。所以这里覆写
 * {@link #boxH()}，把高度夹进可视区，并且所有纵向坐标都由 {@link #boxH()} 反推。
 */
public class NetEaseLoginScreen extends BlackGoldScreen {

    /** 窗口足够大时的理想面板高度。 */
    private static final int DESIGN_H = 302;
    /** 高度下限：再小就把版面压到极限，保证按钮和输入框还能用。 */
    private static final int MIN_H = 200;
    private static final int FIELD_H = 20;
    /** 二维码可用区的上限（正方形边长，格）。 */
    private static final int QR_MAX = 128;
    private static final int QR_MIN = 40;
    /** 二维码静区宽度（以格为单位），ISO 要求 4，这里屏幕小取 2 也足够手机识别。 */
    private static final int QR_QUIET = 2;
    /** 离面板底边的留白。 */
    private static final int BOTTOM_PAD = 12;
    /** 正常轮询间隔，40 tick = 2 秒，与网易云官网页面的节奏一致。 */
    private static final int POLL_INTERVAL_TICKS = 40;
    /** 命中 8821 之后的轮询间隔，200 tick = 10 秒；风控没解除前没必要每 2 秒撞一次墙。 */
    private static final int SLOW_POLL_INTERVAL_TICKS = 200;
    /** 取码失败后的重试间隔，100 tick = 5 秒。 */
    private static final int RETRY_INTERVAL_TICKS = 100;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "DiscStudio-NetEaseLogin");
        thread.setDaemon(true);
        return thread;
    });

    private EditBox cookieField;
    /** 中间那颗按钮，在两种模式间换文案（扫码登录 / 收起二维码），所以要留引用。 */
    private BlackGoldButton modeButton;

    /** 是否处在扫码模式。默认关闭 —— 不发请求，也就不会撞上风控。 */
    private volatile boolean qrMode;
    /** 当前二维码内容；空串表示"没有在等的二维码"。 */
    private volatile String qrContent = "";
    /** 界面底部的状态行。Component 不可变，跨线程读写安全。 */
    private volatile Component status = Component.empty();
    /** 有请求在飞，避免轮询重入。 */
    private volatile boolean busy;
    /** 是否已被风控拦截；只影响轮询节奏。 */
    private volatile boolean riskControlled;
    /** 刚登录成功（扫码或 Cookie）；由 tick 在渲染线程上收尾，自动退回 Cookie 模式。 */
    private volatile boolean justLoggedIn;

    private boolean started;
    private int ticksSincePoll;

    /** 已编码的矩阵缓存，避免每帧重算 1300 多个格子的纠错码。 */
    private String cachedContent = "";
    private boolean[][] cachedModules;

    public NetEaseLoginScreen() {
        super(Component.translatable("gui.netmusic_disc_studio.netease.title"), BlockPos.ZERO);
    }

    // ─────────────────────────── 尺寸与排版 ───────────────────────────

    @Override
    protected int boxX() {
        return Math.max(2, (width - BOX_W) / 2);
    }

    @Override
    protected int boxH() {
        int fit = Math.min(DESIGN_H, height - 12);
        return Math.max(fit, Math.min(MIN_H, height - 4));
    }

    @Override
    protected int boxY() {
        return Math.max(2, (height - boxH()) / 2);
    }

    // 下面这一组"从下往上"排：底部说明 → 按钮 → 输入框 → 状态行，
    // 剩下的空间全给内容区（Cookie 引导或二维码）。改高度只需要动 boxH()。

    private int howtoY() {
        return boxY() + boxH() - BOTTOM_PAD - 9;
    }

    private int buttonY() {
        return howtoY() - 5 - FIELD_H;
    }

    private int fieldY() {
        return buttonY() - 7 - FIELD_H;
    }

    private int statusY() {
        return fieldY() - 20;
    }

    /** 内容区顶边：标题栏下面一点。 */
    private int contentY() {
        return boxY() + HEADER_H + 24;
    }

    private int contentBottom() {
        return statusY() - 8;
    }

    /** 二维码可用区边长；窗口太小时可能小于 {@link #QR_MIN}，那时干脆不画。 */
    private int qrArea() {
        return Math.min(QR_MAX, contentBottom() - contentY());
    }

    // ─────────────────────────── 控件 ───────────────────────────

    @Override
    protected void buildWidgets() {
        int bx = boxX();
        int inner = BOX_W - PAD * 2;

        cookieField = new EditBox(font, bx + PAD, fieldY(), inner, FIELD_H, Component.empty());
        cookieField.setMaxLength(4096);
        cookieField.setHint(Component.translatable("gui.netmusic_disc_studio.netease.cookie_hint"));
        addRenderableWidget(cookieField);

        int third = (inner - 16) / 3;
        int y = buttonY();
        addRenderableWidget(new BlackGoldButton(bx + PAD, y, third, FIELD_H,
                Component.translatable("gui.netmusic_disc_studio.netease.use_cookie"),
                button -> submitCookie(), GOLD));
        modeButton = new BlackGoldButton(bx + PAD + third + 8, y, third, FIELD_H,
                modeButtonLabel(), button -> setQrMode(!qrMode), TEXT_SECONDARY);
        addRenderableWidget(modeButton);
        addRenderableWidget(new BlackGoldButton(bx + PAD + (third + 8) * 2, y, third, FIELD_H,
                Component.translatable("gui.netmusic_disc_studio.netease.logout"),
                button -> logout(), TEXT_SECONDARY));

        // 界面重建（例如改窗口大小）时不要重新申请二维码，否则扫到一半就被换掉了。
        // 这里刻意不主动取码：没点「扫码登录」就一个请求都不发。
        started = true;
    }

    @Override
    protected void onSave() {
    }

    private Component modeButtonLabel() {
        return Component.translatable(qrMode
                ? "gui.netmusic_disc_studio.netease.qr_collapse"
                : "gui.netmusic_disc_studio.netease.qr_button");
    }

    // ─────────────────────────── 扫码模式 ───────────────────────────

    /** 进/出扫码模式。进入时才去申请二维码，退出立刻停止轮询。 */
    private void setQrMode(boolean on) {
        qrMode = on;
        ticksSincePoll = 0;
        riskControlled = false;
        status = Component.empty();
        if (on && qrContent.isEmpty()) {
            startLogin();
        }
        if (modeButton != null) {
            modeButton.setMessage(modeButtonLabel());
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (justLoggedIn) {
            // 登录成功后自动收起二维码：那条路已经不需要了，界面回到 Cookie 形态。
            justLoggedIn = false;
            if (qrMode) {
                setQrMode(false);
            }
        }
        if (!qrMode || busy) {
            return;
        }
        if (qrContent.isEmpty()) {
            // 二维码过期或取码失败后自动重试，5 秒一次，别把风控惹毛。
            if (++ticksSincePoll >= RETRY_INTERVAL_TICKS) {
                ticksSincePoll = 0;
                startLogin();
            }
            return;
        }
        int interval = riskControlled ? SLOW_POLL_INTERVAL_TICKS : POLL_INTERVAL_TICKS;
        if (++ticksSincePoll >= interval) {
            ticksSincePoll = 0;
            pollOnce();
        }
    }

    /** 申请一个新二维码。 */
    private void startLogin() {
        if (busy) {
            return;
        }
        busy = true;
        status = Component.translatable("gui.netmusic_disc_studio.netease.requesting");
        EXECUTOR.execute(() -> {
            try {
                qrContent = NetEaseLoginApi.requestQrContent();
                riskControlled = false;
                status = Component.translatable("gui.netmusic_disc_studio.netease.waiting");
            } catch (Exception exception) {
                DiscStudio.LOGGER.warn("申请网易云二维码失败", exception);
                qrContent = "";
                status = Component.translatable("gui.netmusic_disc_studio.netease.network_failed",
                        reason(exception));
            } finally {
                busy = false;
            }
        });
    }

    /**
     * 轮询一次扫码结果。
     * <p>
     * 二维码过期时要在<b>释放 busy 之后</b>才重新申请，否则 {@code finally} 里的 {@code busy = false}
     * 会把新任务刚设上的标志位覆盖掉，导致两个请求并发跑。所以这里用 restart 标志延后一步。
     */
    private void pollOnce() {
        if (busy) {
            return;
        }
        busy = true;
        String content = qrContent;
        EXECUTOR.execute(() -> {
            boolean restart = false;
            try {
                NetEaseLoginApi.PollResult result = NetEaseLoginApi.poll(content);
                if (result.success()) {
                    NetEaseSession.save(result.cookie(), result.nickname());
                    qrContent = "";
                    riskControlled = false;
                    status = Component.empty();
                    justLoggedIn = true;
                } else if (result.expired()) {
                    // 过期就自动换一张，不让玩家自己点刷新。
                    qrContent = "";
                    status = Component.translatable("gui.netmusic_disc_studio.netease.expired");
                    restart = true;
                } else if (result.scanned()) {
                    status = Component.translatable("gui.netmusic_disc_studio.netease.scanned");
                } else if (result.riskControlled()) {
                    // 8821 是网易云服务端在手机确认那一刻做的判定，客户端改请求头、换端点都没用。
                    // 二维码留着不清（清了那块区域就是一片空白），轮询放慢到 10 秒，别反复撞墙。
                    riskControlled = true;
                    ticksSincePoll = 0;
                    status = Component.translatable("gui.netmusic_disc_studio.netease.risk_controlled");
                } else if (result.waiting()) {
                    status = Component.translatable("gui.netmusic_disc_studio.netease.waiting");
                } else {
                    status = Component.translatable("gui.netmusic_disc_studio.netease.poll_failed",
                            result.code() + (result.message().isBlank() ? "" : " " + result.message()));
                }
            } catch (Exception exception) {
                DiscStudio.LOGGER.warn("轮询网易云扫码状态失败", exception);
                status = Component.translatable("gui.netmusic_disc_studio.netease.network_failed",
                        reason(exception));
            } finally {
                busy = false;
            }
            if (restart) {
                startLogin();
            }
        });
    }

    // ─────────────────────────── Cookie 登录 ───────────────────────────

    /** 用粘贴进来的 Cookie 直接登录。 */
    private void submitCookie() {
        // 先归一化再校验、再存盘：裸 token 会被补成 MUSIC_U=... ，否则播放链路拿到的是非法 Cookie 头。
        String value = NetEaseLoginApi.normalizeCookie(cookieField == null ? "" : cookieField.getValue());
        if (value.isEmpty()) {
            status = Component.translatable("gui.netmusic_disc_studio.netease.cookie_empty");
            return;
        }
        if (busy) {
            return;
        }
        busy = true;
        status = Component.translatable("gui.netmusic_disc_studio.netease.verifying");
        EXECUTOR.execute(() -> {
            try {
                String nickname = NetEaseLoginApi.verifyCookie(value);
                if (nickname == null) {
                    // 校验不通过就别写会话文件，免得后面以为登录成功却放不出歌。
                    status = Component.translatable("gui.netmusic_disc_studio.netease.cookie_invalid");
                    return;
                }
                NetEaseSession.save(value, nickname);
                cookieField.setValue("");
                status = Component.empty();
                justLoggedIn = true;
            } catch (Exception exception) {
                DiscStudio.LOGGER.warn("校验网易云 Cookie 失败", exception);
                status = Component.translatable("gui.netmusic_disc_studio.netease.network_failed",
                        reason(exception));
            } finally {
                busy = false;
            }
        });
    }

    /** 退出登录，回到未登录的 Cookie 引导。 */
    private void logout() {
        NetEaseSession.clear();
        if (cookieField != null) {
            cookieField.setValue("");
        }
        status = Component.translatable("gui.netmusic_disc_studio.netease.logged_out");
        qrContent = "";
        riskControlled = false;
        ticksSincePoll = 0;
        if (qrMode) {
            setQrMode(false);
        }
    }

    private static Component displayName() {
        String nickname = NetEaseSession.nickname();
        return nickname.isBlank()
                ? Component.translatable("gui.netmusic_disc_studio.netease.unnamed")
                : Component.literal(nickname);
    }

    private static String reason(Exception exception) {
        return exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }

    // ─────────────────────────── 绘制 ───────────────────────────

    @Override
    protected void drawContent(GuiGraphicsExtractor g, int bx, int by, int mx, int my) {
        boolean loggedIn = NetEaseSession.loggedIn();
        int centerX = bx + BOX_W / 2;
        int inner = BOX_W - PAD * 2;

        if (qrMode) {
            drawQrView(g, bx, centerX, inner, loggedIn);
        } else {
            drawCookieGuide(g, bx, centerX, inner, loggedIn);
        }

        // 状态行只在实际有话说的时候出现（成功时不写，避免和顶部那行重复）。
        String statusText = status.getString();
        if (!statusText.isEmpty()) {
            g.centeredText(font, Component.literal(BlackGoldUi.ellipsize(font, statusText, inner)),
                    centerX, statusY(), loggedIn ? GOLD : TEXT_PRIMARY);
        }

        // 底部的说明按模式切换：Cookie 模式写"只存本地"，扫码模式提醒风控。
        g.centeredText(font, Component.literal(BlackGoldUi.ellipsize(font,
                        Component.translatable(qrMode
                                ? "gui.netmusic_disc_studio.netease.qr_note"
                                : "gui.netmusic_disc_studio.netease.local_only").getString(),
                        inner)),
                centerX, howtoY(), TEXT_DIM);
    }

    /** Cookie 模式的顶部区域：一行标题 + 三步引导。 */
    private void drawCookieGuide(GuiGraphicsExtractor g, int bx, int centerX, int inner, boolean loggedIn) {
        Component title = loggedIn
                ? Component.translatable("gui.netmusic_disc_studio.netease.already_logged_in", displayName())
                : Component.translatable("gui.netmusic_disc_studio.netease.cookie_title");
        g.centeredText(font, Component.literal(BlackGoldUi.ellipsize(font, title.getString(), inner)),
                centerX, contentY(), loggedIn ? GOLD : TEXT_SECONDARY);

        // 三步引导左对齐排布，整体在内容区里垂直居中，看着不至于头重脚轻。
        String[] steps = {
                "gui.netmusic_disc_studio.netease.step1",
                "gui.netmusic_disc_studio.netease.step2",
                "gui.netmusic_disc_studio.netease.step3",
        };
        int lineHeight = 14;
        int blockHeight = steps.length * lineHeight;
        int startY = contentY() + 16 + Math.max(0, (contentBottom() - contentY() - 16 - blockHeight) / 2);
        for (int i = 0; i < steps.length; i++) {
            String text = Component.translatable(steps[i]).getString();
            g.text(font, BlackGoldUi.ellipsize(font, text, inner), bx + PAD, startY + i * lineHeight,
                    TEXT_SECONDARY, false);
        }
    }

    /** 扫码模式的顶部区域：提示行 + 二维码。 */
    private void drawQrView(GuiGraphicsExtractor g, int bx, int centerX, int inner, boolean loggedIn) {
        Component hint = qrContent.isEmpty()
                ? Component.translatable("gui.netmusic_disc_studio.netease.requesting")
                : Component.translatable("gui.netmusic_disc_studio.netease.scan_hint");
        g.centeredText(font, Component.literal(BlackGoldUi.ellipsize(font, hint.getString(), inner)),
                centerX, contentY(), TEXT_SECONDARY);

        int area = qrArea();
        drawQrCode(g, bx + (BOX_W - area) / 2, contentY() + 16, area);
    }

    /**
     * 把二维码画出来。
     * <p>
     * 逐格 {@code fill} 而不是生成贴图：二维码就是黑白方块，省掉纹理注册、上传与释放，
     * 也不碰图片解码那条曾经踩过坑的路。同色连排会合并成一个矩形，整屏大约几十次绘制调用。
     * <p>
     * 白底直接铺满整个可用区，模块居中——多出来的那圈白就是静区，比 ISO 要求的 4 格还宽。
     */
    private void drawQrCode(GuiGraphicsExtractor g, int areaX, int areaY, int area) {
        if (area < QR_MIN) {
            // 窗口太小，硬画只会糊成一团，索性留白。
            return;
        }
        boolean[][] modules = modules();
        if (modules == null) {
            return;
        }
        int count = modules.length;
        int cell = Math.max(1, (area - QR_QUIET * 2) / (count + QR_QUIET * 2));
        int size = count * cell;
        int qrX = areaX + (area - size) / 2;
        int qrY = areaY + (area - size) / 2;

        // 白底连同静区一起铺出来：深色模块之外必须是浅色，手机才能定位。
        fill(g, areaX, areaY, areaX + area, areaY + area, 0xFFFFFFFF);

        for (int row = 0; row < count; row++) {
            int runStart = -1;
            for (int col = 0; col <= count; col++) {
                boolean dark = col < count && modules[row][col];
                if (dark && runStart < 0) {
                    runStart = col;
                } else if (!dark && runStart >= 0) {
                    fill(g, qrX + runStart * cell, qrY + row * cell,
                            qrX + col * cell, qrY + (row + 1) * cell, 0xFF000000);
                    runStart = -1;
                }
            }
        }
    }

    private static void fill(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color) {
        g.fillGradient(x1, y1, x2, y2, color, color);
    }

    /** 取当前二维码的矩阵，内容变了才重新编码。 */
    private boolean[][] modules() {
        String content = qrContent;
        if (content.isEmpty()) {
            return null;
        }
        if (content.equals(cachedContent)) {
            return cachedModules;
        }
        cachedContent = content;
        try {
            cachedModules = QrCode.encode(content);
        } catch (RuntimeException exception) {
            DiscStudio.LOGGER.warn("二维码编码失败: {}", content, exception);
            cachedModules = null;
        }
        return cachedModules;
    }
}
