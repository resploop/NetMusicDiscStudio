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
 * 网易云账号登录界面：<b>以扫码为主，粘贴 Cookie 是备选</b>。
 * <p>
 * 登录态本身由 {@link NetEaseSession} 保存并注入 NetMusic 的 WebApi，本界面只负责
 * "拿到一条可用的 Cookie"这一件事。
 * <p>
 * <b>扫码是一等路径（2026-09-19 修正）：</b>此前判断"扫码必被风控拦（8821）"，那个结论是错的——
 * 当时的对照实验只换了 User-Agent 与加密/明文端点，唯一真正起作用的变量 {@code type} 没被纳入，
 * 而 {@code type} 恰恰是开关：{@code 1} 是网页扫码（会被拦），{@code 3} 是 PC 客户端通道（不被拦）。
 * 改用 {@code type=3} + 官方桌面版 UA 后扫码恢复正常，细节见
 * {@link NetEaseLoginApi} 的类注释。
 * <p>
 * <b>默认就进扫码（2026-09-19，实测可用之后）：</b>打开界面即进扫码模式，并<b>立刻申请一张二维码</b>，
 * 玩家把手机掏出来扫就行；Cookie 那条路退成备用，点「用 Cookie 登录」才展开输入框。
 * 只有<b>已经登录</b>时才反过来：直接显示"当前已登录：昵称"，不申请二维码——
 * 玩家可能只是来看一眼登录状态，没必要为这张码开一次会话。
 * <p>
 * 两种模式<b>共用同一批控件</b>，切换只改 {@code visible} 与坐标，不重建：MC 的
 * {@code AbstractWidget.isActive()} 就是 {@code visible && active}，渲染、命中测试与焦点导航
 * 都以它为前提，所以"藏起来"的控件点不到也 Tab 不到，不需要真的把控件摘掉。
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

    /** Cookie 输入框；只在 Cookie 模式可见。 */
    private EditBox cookieField;
    /** Cookie 模式下的「Cookie 登录」按钮；扫码模式下藏起来。 */
    private BlackGoldButton cookieButton;
    /** 模式切换按钮：扫码模式下是「用 Cookie 登录」，Cookie 模式下是「扫码登录」，所以要留引用换文案。 */
    private BlackGoldButton modeButton;
    /** 退出登录。两种模式都在，位置随模式变。 */
    private BlackGoldButton logoutButton;

    /**
     * 是否处在扫码模式。
     * <p>
     * 只在渲染线程读写（{@code buildWidgets}/{@code setQrMode}/{@code drawContent} 全在渲染线程），
     * 工作线程不碰它。真正需要跨线程的那些字段是下面 {@link #qrContent} 几个。
     */
    private boolean qrMode;
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

        cookieField = new EditBox(font, bx + PAD, fieldY(), BOX_W - PAD * 2, FIELD_H, Component.empty());
        cookieField.setMaxLength(4096);
        cookieField.setHint(Component.translatable("gui.netmusic_disc_studio.netease.cookie_hint"));
        addRenderableWidget(cookieField);

        // 三个按钮先按 0 尺寸建出来，位置与宽度统一交给 layoutWidgets() —— 两种模式的排布不一样。
        cookieButton = new BlackGoldButton(0, 0, 0, FIELD_H,
                Component.translatable("gui.netmusic_disc_studio.netease.use_cookie"),
                button -> submitCookie(), GOLD);
        addRenderableWidget(cookieButton);
        modeButton = new BlackGoldButton(0, 0, 0, FIELD_H, modeButtonLabel(),
                button -> setQrMode(!qrMode), TEXT_SECONDARY);
        addRenderableWidget(modeButton);
        logoutButton = new BlackGoldButton(0, 0, 0, FIELD_H,
                Component.translatable("gui.netmusic_disc_studio.netease.logout"),
                button -> logout(), TEXT_SECONDARY);
        addRenderableWidget(logoutButton);

        // 只在第一次进来时定默认模式。界面重建（改窗口大小、重载资源）不走这里，
        // 否则玩家自己切到 Cookie 模式后动一下窗口就被弹回扫码，而且正在等的那张码会被换掉。
        boolean first = !started;
        if (first) {
            qrMode = !NetEaseSession.loggedIn();
            started = true;
        }
        layoutWidgets();

        if (first && qrMode) {
            // 打开界面就把码取回来 —— 扫码是主路径，没道理让玩家再点一下。
            // 取码这一步不涉及账号，也不会触发风控（8821 只在手机点确认那一刻由服务端判定）。
            startLogin();
        }
    }

    /**
     * 按当前模式摆放控件。
     * <p>
     * 共用同一批控件、只改 {@code visible} 与坐标，不重建：重建会把正在等的那张二维码连同
     * 输入框里已经贴了一半的 Cookie 一起丢掉。隐藏侧的安全性见类注释（{@code visible=false}
     * ⇒ 渲染、点击、Tab 焦点三条路全都够不着）。
     */
    private void layoutWidgets() {
        int bx = boxX();
        int inner = BOX_W - PAD * 2;
        int y = buttonY();

        if (cookieField != null) {
            cookieField.setX(bx + PAD);
            cookieField.setY(fieldY());
            cookieField.setWidth(inner);
            cookieField.setVisible(!qrMode);
        }

        if (qrMode) {
            // 扫码模式：输入框收起来，那一排只放「用 Cookie 登录 / 退出登录」。
            int half = (inner - 8) / 2;
            place(modeButton, bx + PAD, y, half);
            place(logoutButton, bx + PAD + half + 8, y, half);
        } else {
            int third = (inner - 16) / 3;
            place(cookieButton, bx + PAD, y, third);
            place(modeButton, bx + PAD + third + 8, y, third);
            place(logoutButton, bx + PAD + (third + 8) * 2, y, third);
        }
        show(cookieButton, !qrMode);

        if (modeButton != null) {
            modeButton.setMessage(modeButtonLabel());
        }

        if (qrMode) {
            // 收起来的输入框不能留着焦点，否则玩家打的字会进到一个看不见的框里。
            if (getFocused() == cookieField) {
                clearFocus();
            }
        } else if (cookieField != null) {
            // 切到 Cookie 模式就把光标放进输入框，玩家 Ctrl+V 即可。
            setFocused(cookieField);
        }
    }

    private static void place(BlackGoldButton button, int x, int y, int width) {
        if (button == null) {
            return;
        }
        button.setX(x);
        button.setY(y);
        button.setWidth(width);
        button.visible = true;
    }

    private static void show(BlackGoldButton button, boolean visible) {
        if (button != null) {
            button.visible = visible;
        }
    }

    @Override
    protected void onSave() {
    }

    private Component modeButtonLabel() {
        return Component.translatable(qrMode
                ? "gui.netmusic_disc_studio.netease.switch_to_cookie"
                : "gui.netmusic_disc_studio.netease.qr_button");
    }

    // ─────────────────────────── 扫码模式 ───────────────────────────

    /** 进/出扫码模式。进入时（且手上没有码）才去申请二维码，退出立刻停止轮询。 */
    private void setQrMode(boolean on) {
        qrMode = on;
        ticksSincePoll = 0;
        riskControlled = false;
        status = Component.empty();
        if (on && qrContent.isEmpty()) {
            startLogin();
        }
        layoutWidgets();
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

    /** 退出登录，回到扫码这条主路径。 */
    private void logout() {
        NetEaseSession.clear();
        if (cookieField != null) {
            cookieField.setValue("");
        }
        // 旧的那张码连着上一个账号的会话，清掉；setQrMode(true) 里会顺带申请一张新的。
        qrContent = "";
        riskControlled = false;
        ticksSincePoll = 0;
        setQrMode(true);
        status = Component.translatable("gui.netmusic_disc_studio.netease.logged_out");
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
