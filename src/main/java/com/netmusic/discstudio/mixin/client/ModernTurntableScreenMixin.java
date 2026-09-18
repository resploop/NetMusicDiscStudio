package com.netmusic.discstudio.mixin.client;

import com.netmusic.discstudio.client.DiscStudioScreens;
import com.netmusic.discstudio.disc.AlbumPlaylist;
import com.netmusic.discstudio.disc.DiscLoopMode;
import com.netmusic.discstudio.disc.LoopModeHolder;
import com.netmusic.discstudio.disc.NetworkDiscs;
import com.netmusic.discstudio.network.DiscStudioTrackPacket;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.gui.BlackGoldButton;
import com.zhongbai233.net_music_can_play_bili.gui.ModernTurntableScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在现代化唱片机的界面里加一行按钮：上一曲 / 选曲 / 下一曲，并把上游那颗
 * 「单曲循环」按钮改写成循环模式的显示。
 * <p>
 * 随机模式下，那一行的「上一曲 / 下一曲」会一起变成「换一首」——服务端在随机模式下
 * 对两颗按钮都做随机跳转，文案不改会让人以为按坏了。
 * <p>
 * 上游界面本身没有播放列表的概念（只有单曲循环），所以"上一曲/下一曲"和
 * "打开曲目列表"这两个入口由本模组补上。
 * <p>
 * 中间那个按钮是<b>上下文相关</b>的：
 * 槽内是「可擦写网络唱片」时是「✎ 换 BV」，槽内是「网络CD」时是「☰ 选曲」。
 * 无论如何，网络CD 的曲目表写入都只能在 NetMusic 的唱片刻录机里完成。
 * <p>
 * 右上那颗按钮仍是上游的 {@code repeatOneButton}（点击照旧发
 * {@code TOGGLE_REPEAT_ONE}，服务端由 {@code ModernTurntableBlockEntityMixin}
 * 翻译成模式轮转），这里只把它显示的「单曲 / 循环中」换成
 * 「顺序 / 单曲 / 列表 / 随机」。
 * <p>
 * 布局说明：上游 {@code ModernTurntableScreen} 的面板是 340×210，
 * 频谱条占 {@code boxY+94..boxY+118}，红石/提取按钮在 {@code boxY+142}，
 * 中间 {@code boxY+120..boxY+140} 是唯一空档，这一行按钮就放在那里。
 * 按钮横向位置由 {@code boxX()} 加内边距推出来（面板宽度是
 * {@code width - 2 * boxX()}），不需要再写死 340 这个常量。
 * 如果上游改版重排了布局，需要同步调整 {@link #DISC_STUDIO_ROW_OFFSET_Y}。
 */
@Mixin(ModernTurntableScreen.class)
public abstract class ModernTurntableScreenMixin {

    @Shadow
    protected abstract int boxX();

    @Shadow
    protected abstract int boxY();

    /**
     * 上游那颗「单曲循环」按钮。字段名必须与目标类一致，因此这里不带 {@code discstudio$} 前缀。
     */
    @Shadow
    private BlackGoldButton repeatOneButton;

    @Unique
    private static final int DISC_STUDIO_ROW_OFFSET_Y = 121;

    @Unique
    private BlackGoldButton discstudio$prevButton;
    @Unique
    private BlackGoldButton discstudio$actionButton;
    @Unique
    private BlackGoldButton discstudio$nextButton;

    @Inject(method = "buildWidgets", at = @At("TAIL"))
    private void discstudio$addPickerButtons(CallbackInfo ci) {
        // 上游布局常量：左右内边距 16、按钮高 18、按钮间距 4。
        final int pad = 16;
        final int buttonHeight = 18;
        final int gap = 4;

        int bx = boxX();
        int by = boxY();
        int panelWidth = ((Screen) (Object) this).width - 2 * bx;
        int inner = panelWidth - pad * 2;
        int buttonWidth = (inner - gap * 2) / 3;
        int rowY = by + DISC_STUDIO_ROW_OFFSET_Y;
        int left = bx + pad;

        ScreenInvoker invoker = (ScreenInvoker) (Object) this;
        discstudio$prevButton = invoker.discstudio$addRenderableWidget(new BlackGoldButton(
                left, rowY, buttonWidth, buttonHeight,
                Component.translatable("gui.netmusic_disc_studio.track.prev"),
                button -> discstudio$sendTrack(DiscStudioTrackPacket.TrackAction.PREV), 0xFFD4A843));
        discstudio$actionButton = invoker.discstudio$addRenderableWidget(new BlackGoldButton(
                left + buttonWidth + gap, rowY, buttonWidth, buttonHeight,
                Component.translatable("gui.netmusic_disc_studio.open_picker"),
                button -> discstudio$onActionButton(), 0xFFD4A843));
        discstudio$nextButton = invoker.discstudio$addRenderableWidget(new BlackGoldButton(
                left + (buttonWidth + gap) * 2, rowY, buttonWidth, buttonHeight,
                Component.translatable("gui.netmusic_disc_studio.track.next"),
                button -> discstudio$sendTrack(DiscStudioTrackPacket.TrackAction.NEXT), 0xFFD4A843));
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void discstudio$refreshPickerButtons(CallbackInfo ci) {
        ModernTurntableBlockEntity turntable = discstudio$turntable();
        boolean hasPlaylist = false;
        boolean erasable = false;
        if (turntable != null && turntable.hasDisc()) {
            ItemStack disc = turntable.getDisc();
            AlbumPlaylist playlist = NetworkDiscs.playlist(disc);
            hasPlaylist = playlist != null && !playlist.isEmpty();
            erasable = NetworkDiscs.isErasableDisc(disc);
        }
        if (discstudio$prevButton != null) {
            discstudio$prevButton.active = hasPlaylist;
        }
        if (discstudio$nextButton != null) {
            discstudio$nextButton.active = hasPlaylist;
        }
        if (discstudio$actionButton != null) {
            // 中间按钮是上下文相关的：可擦写唱片给「换 BV」，网络CD 给「选曲」。
            discstudio$actionButton.active = hasPlaylist || erasable;
            discstudio$actionButton.setMessage(Component.translatable(
                    erasable ? "gui.netmusic_disc_studio.open_swap_bv"
                             : "gui.netmusic_disc_studio.open_picker"));
        }
    }

    /**
     * 把上游那颗「单曲循环」按钮的文案换成当前循环模式。
     * <p>
     * 值本身由服务端算好后随方块实体同步下来，这里只负责把它翻译成一个短标签。
     * 之所以不另加一颗按钮，是因为界面横向已经排满（面板 340 宽，一行只放得下三颗），
     * 而这颗按钮的语义本来就和循环模式是同一件事。
     */
    @Inject(method = "refreshWidgets", at = @At("TAIL"))
    private void discstudio$refreshLoopModeLabel(CallbackInfo ci) {
        if (repeatOneButton == null) {
            return;
        }
        DiscLoopMode mode = discstudio$effectiveLoopMode(discstudio$turntable());
        repeatOneButton.setMessage(Component.translatable(switch (mode) {
            case SEQUENTIAL -> "gui.netmusic_disc_studio.loop.sequential";
            case REPEAT_ONE -> "gui.netmusic_disc_studio.loop.single";
            case REPEAT_ALL -> "gui.netmusic_disc_studio.loop.list";
            case SHUFFLE -> "gui.netmusic_disc_studio.loop.shuffle";
        }));
        discstudio$relabelTrackButtons(mode == DiscLoopMode.SHUFFLE);
    }

    /**
     * 随机模式下把「上一曲 / 下一曲」改成同一句「换一首」。
     * <p>
     * 随机模式没有"顺序"可言，服务端那边两颗按钮都会随机跳，文案跟着改才不会让人以为
     * 「上一曲」按下去坏了。其余模式恢复成上一曲 / 下一曲。
     */
    @Unique
    private void discstudio$relabelTrackButtons(boolean shuffle) {
        if (discstudio$prevButton == null || discstudio$nextButton == null) {
            return;
        }
        if (shuffle) {
            Component relabel = Component.translatable("gui.netmusic_disc_studio.track.shuffle");
            discstudio$prevButton.setMessage(relabel);
            discstudio$nextButton.setMessage(relabel);
            return;
        }
        discstudio$prevButton.setMessage(Component.translatable("gui.netmusic_disc_studio.track.prev"));
        discstudio$nextButton.setMessage(Component.translatable("gui.netmusic_disc_studio.track.next"));
    }

    @Unique
    private static DiscLoopMode discstudio$effectiveLoopMode(ModernTurntableBlockEntity turntable) {
        if (turntable == null) {
            return DiscLoopMode.SEQUENTIAL;
        }
        DiscLoopMode raw = turntable instanceof LoopModeHolder holder
                ? holder.discstudio$loopMode()
                : DiscLoopMode.SEQUENTIAL;
        // 曲目表只有一首时，「列表」「随机」都与「单曲」等价，服务端那边会走"放完就停"，
        // 所以按钮也跟着显示「顺序」，免得文案和听感对不上。
        AlbumPlaylist playlist = NetworkDiscs.playlist(turntable.getDisc());
        return raw.effective(playlist != null && playlist.size() > 1);
    }

    @Unique
    private void discstudio$sendTrack(DiscStudioTrackPacket.TrackAction action) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null) {
            return;
        }
        minecraft.getConnection().send(new DiscStudioTrackPacket(discstudio$blockPos(), action, 0));
    }

    @Unique
    private void discstudio$onActionButton() {
        ModernTurntableBlockEntity turntable = discstudio$turntable();
        if (turntable == null || !turntable.hasDisc()) {
            return;
        }
        ItemStack disc = turntable.getDisc();
        if (NetworkDiscs.isErasableDisc(disc)) {
            DiscStudioScreens.openSwapBv(discstudio$blockPos());
            return;
        }
        AlbumPlaylist playlist = NetworkDiscs.playlist(disc);
        if (playlist != null && !playlist.isEmpty()) {
            DiscStudioScreens.openTrackPicker(discstudio$blockPos());
        }
    }

    @Unique
    private BlockPos discstudio$blockPos() {
        return ((BlackGoldScreenAccessor) (Object) this).discstudio$blockPos();
    }

    @Unique
    private ModernTurntableBlockEntity discstudio$turntable() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return null;
        }
        return minecraft.level.getBlockEntity(discstudio$blockPos()) instanceof ModernTurntableBlockEntity turntable
                ? turntable
                : null;
    }
}
