package com.netmusic.discstudio.client.screen;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.netmusic.discstudio.disc.NetworkDiscs;
import com.netmusic.discstudio.network.SwapBvPacket;
import com.zhongbai233.net_music_can_play_bili.gui.BlackGoldButton;
import com.zhongbai233.net_music_can_play_bili.gui.BlackGoldUi;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * 在唱片机上给「可擦写网络唱片」换 BV 的界面。
 * <p>
 * 只在玩家主动点「✎ 换 BV」时打开——放进唱片机时不会自动弹出，
 * 以免打断已经刻好的唱片的播放。
 * <p>
 * 分P 框默认填 1；此时 {@code BiliAudioResolver} 会采用链接里自带的 {@code ?p=}，
 * 所以直接粘一条带分 P 的链接也能正确落到对应分 P。
 */
public class SwapBvScreen extends DiscStudioScreen {
    private static final int FIELD_H = 20;
    private static final int BOX_HEIGHT = 166;
    private static final int PENDING_TIMEOUT_TICKS = 600;

    private EditBox inputField;
    private EditBox pageField;
    private WriteStatus status = WriteStatus.IDLE;
    private int pendingTicks;

    public SwapBvScreen(BlockPos pos) {
        super(Component.translatable("gui.netmusic_disc_studio.swap_bv.title"), pos);
    }

    @Override
    protected int boxH() {
        return BOX_HEIGHT;
    }

    @Override
    protected void buildWidgets() {
        int bx = boxX();
        int by = boxY();
        int inner = BOX_W - PAD * 2;
        ItemStack disc = currentDisc();

        inputField = new EditBox(font, bx + PAD, by + HEADER_H + 24, inner, FIELD_H, Component.empty());
        inputField.setMaxLength(512);
        inputField.setHint(Component.translatable("gui.netmusic_disc_studio.swap_bv.hint"));
        inputField.setValue(NetworkDiscs.source(disc));
        addRenderableWidget(inputField);

        pageField = new EditBox(font, bx + PAD, by + HEADER_H + 62, 48, FIELD_H - 2, Component.literal("P"));
        pageField.setMaxLength(4);
        pageField.setValue("1");
        addRenderableWidget(pageField);

        int half = (inner - 8) / 2;
        addRenderableWidget(new BlackGoldButton(bx + PAD, by + HEADER_H + 90, half, FIELD_H,
                Component.translatable("gui.netmusic_disc_studio.swap_bv.write"),
                button -> submit(), GOLD));
        addRenderableWidget(new BlackGoldButton(bx + PAD + half + 8, by + HEADER_H + 90, half, FIELD_H,
                Component.translatable("gui.netmusic_disc_studio.swap_bv.clear"),
                button -> {
                    if (inputField != null) {
                        inputField.setValue("");
                    }
                    status = WriteStatus.IDLE;
                }, TEXT_SECONDARY));
    }

    @Override
    protected void onSave() {
    }

    @Override
    public void tick() {
        super.tick();
        if (status == WriteStatus.WORKING) {
            if (++pendingTicks > PENDING_TIMEOUT_TICKS) {
                status = WriteStatus.IDLE;
                pendingTicks = 0;
            }
        } else {
            pendingTicks = 0;
        }
    }

    private void submit() {
        String input = inputField == null ? "" : inputField.getValue().trim();
        if (input.isEmpty()) {
            status = WriteStatus.EMPTY_INPUT;
            return;
        }
        int page = 1;
        String pageText = pageField == null ? "" : pageField.getValue().trim();
        if (!pageText.isEmpty()) {
            try {
                page = Integer.parseInt(pageText);
            } catch (NumberFormatException exception) {
                status = WriteStatus.BAD_PAGE;
                return;
            }
        }
        if (page < 1) {
            status = WriteStatus.BAD_PAGE;
            return;
        }
        status = WriteStatus.WORKING;
        pendingTicks = 0;
        sendPacket(new SwapBvPacket(blockPos, input, page));
    }

    @Override
    protected void drawContent(GuiGraphicsExtractor g, int bx, int by, int mx, int my) {
        int cx = bx + BOX_W / 2;
        g.centeredText(font, Component.translatable("gui.netmusic_disc_studio.swap_bv.input_label"),
                cx, by + HEADER_H + 8, TEXT_SECONDARY);
        g.text(font, Component.translatable("gui.netmusic_disc_studio.swap_bv.page_label"),
                bx + PAD, by + HEADER_H + 52, TEXT_SECONDARY, false);

        int statusY = by + HEADER_H + 116;
        if (status == WriteStatus.IDLE) {
            ItemMusicCD.SongInfo info = NetworkDiscs.songInfo(currentDisc());
            if (info == null || info.songName == null || info.songName.isBlank()) {
                g.centeredText(font, Component.translatable("gui.netmusic_disc_studio.swap_bv.empty"),
                        cx, statusY, TEXT_DIM);
            } else {
                String label = BlackGoldUi.ellipsize(font, info.songName, BOX_W - PAD * 2);
                g.centeredText(font, Component.literal(label), cx, statusY, GOLD);
            }
            return;
        }
        g.centeredText(font, Component.translatable(status.translationKey), cx, statusY,
                status == WriteStatus.WORKING ? TEXT_PRIMARY : 0xFFE06C6C);
    }

    /** 界面底部的状态提示。 */
    private enum WriteStatus {
        IDLE(""),
        WORKING("gui.netmusic_disc_studio.status.working"),
        EMPTY_INPUT("gui.netmusic_disc_studio.status.empty_input"),
        BAD_PAGE("gui.netmusic_disc_studio.status.bad_page");

        private final String translationKey;

        WriteStatus(String translationKey) {
            this.translationKey = translationKey;
        }
    }
}
