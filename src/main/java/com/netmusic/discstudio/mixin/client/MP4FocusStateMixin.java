package com.netmusic.discstudio.mixin.client;

import com.netmusic.discstudio.client.album.Mp4AlbumView;
import com.zhongbai233.net_music_can_play_bili.client.MP4Client;
import com.zhongbai233.net_music_can_play_bili.client.MP4FocusState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * MP4 界面里的「专辑折叠行」。
 * <p>
 * MP4 的界面由离屏渲染器 {@code MP4OffscreenGuiRenderer} 画到物品屏幕上，
 * 但它的每一行数据都来自 {@link MP4FocusState} 的四个静态取值方法
 * （{@code queueSize} / {@code queueTitle} / {@code queueScrollOffset} / {@code selectedQueueIndex}），
 * 点击则由 {@code MP4FocusScreen} → {@code selectVisibleQueueRow} 分发。
 * <p>
 * 所以这里<b>只覆写这几个静态方法</b>，把"显示行空间"与"MP4 真实队列下标"分成两套坐标：
 *
 * <ul>
 *   <li>队列里有专辑时，{@code queueSize} 返回<b>组数</b>，
 *       {@code queueTitle} 让专辑那行显示成 {@code [专辑] 名称 (N首) >}；</li>
 *   <li>点专辑行 → {@link Mp4AlbumView#open(int)}，显示行换成该专辑的曲目，
 *       第 0 行是 {@code < 返回}；</li>
 *   <li>点曲目行 → 把真实队列下标写回 {@code selectedQueueIndex} 并同步给服务端，
 *       之后播放 / 切歌 / 单曲循环 / 顺序 / 列表循环全部由上游原有逻辑处理。</li>
 * </ul>
 *
 * <p>渲染器与 {@code MP4GuiViewState} 一行都不用改。
 * 队列里一张专辑都没有时所有注入都会让路（{@link Mp4AlbumView#active()} 为 false），
 * 此时 MP4 的行为与打本模组之前完全一致。
 */
@Mixin(MP4FocusState.class)
public abstract class MP4FocusStateMixin {

    /** MP4 真实队列下标（上游字段，始终是"真实"的那个）。 */
    @Shadow
    private static int selectedQueueIndex;

    /** 与上游 {@code selectVisibleQueueRow} 一样，切行时把进度归零。 */
    @Shadow
    private static float mediaProgress;

    // ───────────────────── 数据来源 ─────────────────────

    @Inject(method = "loadQueue", at = @At("TAIL"))
    private static void discstudio$captureQueue(List<ItemStack> queue, CallbackInfo ci) {
        Mp4AlbumView.capture(queue);
    }

    @Inject(method = "resetAll", at = @At("TAIL"))
    private static void discstudio$resetView(CallbackInfo ci) {
        Mp4AlbumView.reset();
    }

    /** 每次打开手持界面都从队列列表开始，不要继承上次进到哪张专辑里。 */
    @Inject(method = "activate", at = @At("TAIL"))
    private static void discstudio$resetOpenState(InteractionHand focusHand, CallbackInfo ci) {
        Mp4AlbumView.close();
    }

    // ───────────────────── 显示行空间 ─────────────────────

    @Inject(method = "queueSize", at = @At("HEAD"), cancellable = true)
    private static void discstudio$queueSize(CallbackInfoReturnable<Integer> cir) {
        if (Mp4AlbumView.active()) {
            cir.setReturnValue(Mp4AlbumView.displaySize());
        }
    }

    @Inject(method = "queueTitle", at = @At("HEAD"), cancellable = true)
    private static void discstudio$queueTitle(int index, CallbackInfoReturnable<String> cir) {
        if (Mp4AlbumView.active()) {
            cir.setReturnValue(Mp4AlbumView.displayTitle(index));
        }
    }

    @Inject(method = "queueScrollOffset", at = @At("HEAD"), cancellable = true)
    private static void discstudio$queueScrollOffset(CallbackInfoReturnable<Integer> cir) {
        if (Mp4AlbumView.active()) {
            cir.setReturnValue(Mp4AlbumView.scrollOffset());
        }
    }

    /** 高亮当前播放项：返回它在显示行空间里的位置。 */
    @Inject(method = "selectedQueueIndex", at = @At("HEAD"), cancellable = true)
    private static void discstudio$selectedQueueIndex(CallbackInfoReturnable<Integer> cir) {
        if (Mp4AlbumView.active()) {
            cir.setReturnValue(Mp4AlbumView.displayIndexOf(selectedQueueIndex));
        }
    }

    // ───────────────────── 滚动与点击 ─────────────────────

    @Inject(method = "scrollQueue", at = @At("HEAD"), cancellable = true)
    private static void discstudio$scrollQueue(double scrollY, CallbackInfo ci) {
        if (!Mp4AlbumView.active()) {
            return;
        }
        Mp4AlbumView.scrollBy(scrollY < 0 ? 1 : -1);
        MP4FocusState.showControlsTemporarily();
        ci.cancel();
    }

    @Inject(method = "selectVisibleQueueRow", at = @At("HEAD"), cancellable = true)
    private static void discstudio$selectVisibleQueueRow(int row, CallbackInfo ci) {
        if (!Mp4AlbumView.active()) {
            return;
        }
        int visibleRows = MP4FocusState.landscape()
                ? MP4FocusState.LANDSCAPE_QUEUE_VISIBLE_ROWS
                : MP4FocusState.PORTRAIT_QUEUE_VISIBLE_ROWS;
        if (row < 0 || row >= visibleRows) {
            // 命中的是列表顶部的标题栏区域（上游对这里传 row = -1）。
            // 正在专辑里就当作"返回"，否则什么都不做。
            if (Mp4AlbumView.albumOpen()) {
                Mp4AlbumView.close();
                ci.cancel();
            }
            return;
        }
        int displayRow = Mp4AlbumView.scrollOffset() + row;
        if (displayRow >= Mp4AlbumView.displaySize()) {
            ci.cancel();
            return;
        }
        if (Mp4AlbumView.isBackRow(displayRow)) {
            Mp4AlbumView.close();
            MP4FocusState.showControlsTemporarily();
            ci.cancel();
            return;
        }
        if (Mp4AlbumView.isAlbumRow(displayRow)) {
            Mp4AlbumView.open(displayRow);
            MP4FocusState.showControlsTemporarily();
            ci.cancel();
            return;
        }
        int target = Mp4AlbumView.realIndexOf(displayRow);
        if (target < 0) {
            ci.cancel();
            return;
        }
        selectedQueueIndex = target;
        mediaProgress = 0.0F;
        Mp4AlbumView.ensureVisible(target);
        MP4FocusState.showControlsTemporarily();
        // 与上游 selectVisibleQueueRow 的收尾一致：把新选中的下标推给服务端，由它换源起播。
        MP4Client.syncFocusedStateToServer();
        ci.cancel();
    }

    // ───────────────────── 上/下一首：必须在「真实下标」空间里走 ─────────────────────

    /*
     * 上游这三个方法都用 queueSize() 来夹取下标，而 queueSize() 在本模组里已被改成
     * 显示行数（一行 = 一张专辑），拿它去夹真实下标会把选中项夹到错误的曲目上。
     * 所以只要队列里有专辑，就由本模组用真实长度重新实现一遍。
     */

    @Inject(method = "nextTrack", at = @At("HEAD"), cancellable = true)
    private static void discstudio$nextTrack(CallbackInfo ci) {
        if (!Mp4AlbumView.active()) {
            return;
        }
        int size = Mp4AlbumView.realQueueSize();
        selectedQueueIndex = size <= 0 ? 0 : Math.min(size - 1, selectedQueueIndex + 1);
        mediaProgress = 0.0F;
        Mp4AlbumView.ensureVisible(selectedQueueIndex);
        MP4FocusState.showControlsTemporarily();
        ci.cancel();
    }

    @Inject(method = "previousTrack", at = @At("HEAD"), cancellable = true)
    private static void discstudio$previousTrack(CallbackInfo ci) {
        if (!Mp4AlbumView.active()) {
            return;
        }
        int size = Mp4AlbumView.realQueueSize();
        selectedQueueIndex = size <= 0 ? 0 : Math.max(0, selectedQueueIndex - 1);
        mediaProgress = 0.0F;
        Mp4AlbumView.ensureVisible(selectedQueueIndex);
        MP4FocusState.showControlsTemporarily();
        ci.cancel();
    }

    @Inject(method = "selectQueueIndexForPlayback", at = @At("HEAD"), cancellable = true)
    private static void discstudio$selectQueueIndexForPlayback(int index, CallbackInfo ci) {
        if (!Mp4AlbumView.active()) {
            return;
        }
        int size = Mp4AlbumView.realQueueSize();
        selectedQueueIndex = size <= 0 ? 0 : Math.max(0, Math.min(size - 1, index));
        mediaProgress = 0.0F;
        Mp4AlbumView.ensureVisible(selectedQueueIndex);
        MP4FocusState.showControlsTemporarily();
        ci.cancel();
    }
}
