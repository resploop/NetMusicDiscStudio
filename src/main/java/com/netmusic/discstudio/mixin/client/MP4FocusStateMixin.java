package com.netmusic.discstudio.mixin.client;

import com.netmusic.discstudio.client.Mp4SeekHold;
import com.netmusic.discstudio.client.Mp4TrackGuard;
import com.netmusic.discstudio.client.album.Mp4AlbumPlayback;
import com.netmusic.discstudio.client.album.Mp4AlbumView;
import com.netmusic.discstudio.client.album.Mp4RealState;
import com.zhongbai233.net_music_can_play_bili.client.MP4FocusState;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * MP4 界面的「专辑行」与「专辑内切歌」。
 *
 * <p>队列模型是"一张网络CD = 一条队列项"，所以这里<b>不改</b>
 * {@link MP4FocusState} 的任何取值方法 —— 队列长度、选中下标、标题对上游而言
 * 始终是真实值，发给服务端的控制包自然也就带的是真实下标。
 * 显示层的折叠只发生在渲染器取的快照上（见 {@code MP4GuiViewStateMixin}）。
 *
 * <p>本 Mixin 只做五件事：
 * <ol>
 *   <li>跟着 {@code loadQueue} 维护一份队列快照（{@link Mp4AlbumView}）；</li>
 *   <li>打开界面时<b>保留</b>上次进到的那张专辑（收起再拿出来还在原处）；</li>
 *   <li>把列表点击分发到"进专辑 / 点曲目 / 返回"；</li>
 *   <li>接管上一曲 / 下一曲：专辑内先按曲目走，走到头才跨到相邻那张碟；
 *       随机播放打开时按视图范围随机跳（上游的 {@code shuffle} 是只显示不生效的）；</li>
 *   <li>拖进度条：基数改用真实在播曲目的时长，松手后开一小段窗口防止进度条被弹回去。</li>
 * </ol>
 *
 * <p>队列里没有专辑、也没开随机播放时，所有注入都会让路，
 * MP4 的行为与打本模组之前完全一致。
 */
@Mixin(MP4FocusState.class)
public abstract class MP4FocusStateMixin {

    /** MP4 真实队列下标（上游字段）。 */
    @Shadow
    private static int selectedQueueIndex;

    /** 切歌时与上游 {@code selectVisibleQueueRow} 一样把进度归零。 */
    @Shadow
    private static float mediaProgress;

    // ───────────────────── 队列快照 ─────────────────────

    @Inject(method = "loadQueue", at = @At("TAIL"))
    private static void discstudio$captureQueue(List<ItemStack> queue, CallbackInfo ci) {
        Mp4AlbumView.capture(queue);
    }

    @Inject(method = "resetAll", at = @At("TAIL"))
    private static void discstudio$resetView(CallbackInfo ci) {
        Mp4AlbumView.reset();
    }

    /**
     * 收起 MP4 再拿出来，<b>还停在原来那张专辑的曲目表里</b>。
     * <p>
     * 这里以前会无条件 {@code close()}（退回队列列表层）。结果是：在专辑里点了一首歌、
     * 收起 MP4 再拿出来，视图被踢回上级目录 —— 而此时"随机播放"是按视图决定范围的
     * （打开着正在播的那张专辑就在专辑内随机，否则整队列随机），于是按下一曲
     * 会直接跳到别的专辑去。保留打开状态后这两个现象一起消失。
     * <p>
     * 队列本身变了也没关系：{@link Mp4AlbumView#capture} 会在新队列里按专辑标识找回它，
     * 真的被拿走了才退回列表层。玩家自己按「返回」时 {@code openedKey} 已被清掉，
     * 收起再打开自然还是列表层 —— 该退的时候照样退。
     */
    @Inject(method = "activate", at = @At("TAIL"))
    private static void discstudio$keepOpenedAlbum(InteractionHand focusHand, CallbackInfo ci) {
        // 打开界面时手上的物品 NBT 才是最新的（可能刚被别人改过曲目下标）。
        Mp4AlbumView.requestRefresh();
    }

    /**
     * 专辑切曲只改物品 NBT，客户端那份副本靠容器同步过来，不会触发 {@code loadQueue}。
     * 这里在界面 tick 里兜底把快照拉齐，否则列表高亮会停在进界面时的那一首。
     *
     * <p>顺便做一件更要紧的事：<b>换了曲子就把界面进度归零</b>。上游把"播到哪儿"记成一个
     * 千分比（{@link #mediaProgress}），到处都用它反乘曲长算绝对位置；而"曲子换了"这件事
     * 上游完全不知道（本模组一张碟占一条队列项，队列下标没变）。不归零的话，下一首一边播
     * 进度条一边顶在上一首的位置上，玩家下一次一碰进度条就会把上一首的位置发出去。
     * 身份判定见 {@link Mp4TrackGuard}。
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private static void discstudio$tickSync(CallbackInfo ci) {
        Mp4AlbumView.tickSync();
        // 注意 observe 是<b>非消费式</b>的：它只更新身份、开窗口，返回"这一刻变了"。
        // 关不关界面都要更新 —— 这里只是顺便每刻都刷一次而已。
        Mp4TrackGuard.observeCurrent();
        discstudio$voidStaleMirror();
    }

    // ───────────────────── 「这份进度属于哪一首」─────────────────────

    /**
     * 换过曲子之后，把这份"播到哪儿"按 0 看待。
     *
     * <p><b>为什么必须在这里做</b>：上游只把进度记成一个千分比，没有任何"它属于哪一首"的标记，
     * 而它会被三种方式反复使用 —— 画进度条（{@link #discstudio$fenceMirrorRead}）、
     * 推给服务端（{@link #discstudio$fenceMirrorPush}）、算 SEEK 目标。
     * 只要有一个地方漏了，玩家点过的那个百分比就会粘到下一首上。
     *
     * <p>只改这一个字段，三种用法就同时被覆盖了。判定见 {@link Mp4TrackGuard#mirrorIsVoid()}：
     * 换过曲、且玩家还没在这一首上亲手设过位置（正在拖进度条时就听他的）。
     */
    @Unique
    private static void discstudio$voidStaleMirror() {
        if (Mp4TrackGuard.mirrorIsVoid()) {
            mediaProgress = 0.0F;
        }
    }

    /**
     * 读进度条的口（渲染、{@code save()}、{@code currentProgressMillis()} 全都会走这里）。
     * <p>界面关着的时候 {@code tick} 根本不跑，而这个 getter 每帧都被渲染器调用 ——
     * 换曲后"进度条停在我之前点的位置"就是在这里被看出来的。
     */
    @Inject(method = "mediaProgress", at = @At("HEAD"))
    private static void discstudio$fenceMirrorRead(CallbackInfoReturnable<Float> cir) {
        discstudio$voidStaleMirror();
    }

    /**
     * 把进度推给服务端的口。
     *
     * <p>{@code MP4Client#cacheFocusedState} 与 {@code cacheFocusedStateFromActiveStack} 都调
     * {@code save()}，而界面上任何点击/滚动/{@code onClose} 都会触发一次推送 ——
     * 这是千分比流向服务端的<b>唯一漏斗</b>。上一首点过的位置就是从这里变成
     * "新曲子的 progressPerMille"的，服务端再拿它乘新曲长 = 从那个位置起播。
     */
    @Inject(method = "save", at = @At("HEAD"))
    private static void discstudio$fenceMirrorPush(CallbackInfoReturnable<MP4Item.State> cir) {
        discstudio$voidStaleMirror();
    }

    // ───────────────────── 拖进度条 ─────────────────────

    /**
     * 拖进度条时「拖到百分之几」要换算成绝对毫秒，基数必须是<b>真实在播曲目</b>的时长。
     * <p>
     * 上游用的是客户端缓存的队列表时长，而这份缓存在网络CD换曲后会滞后若干刻
     * （换曲改的是物品 NBT，缓存只在服务端推状态时刷新）。基数偏大时，
     * 拖到某个位置算出的目标会超出真实曲长，服务端把它夹到"结尾前 50 毫秒"，
     * 曲子瞬间"播完" —— 听起来就是<b>进度条动了但没声音</b>，随机性取决于
     * 新旧曲目的长短差和拖到的位置，所以表现为"有时候"。
     * <p>
     * 本机播放注册表里的时长来自服务端对当前曲目直链的解析结果，与在播内容严格一致。
     * 只在队列里真有专辑时接管，普通唱片仍然走上游原值。
     */
    @Inject(method = "selectedTrackDurationMillis", at = @At("HEAD"), cancellable = true)
    private static void discstudio$liveTrackDuration(CallbackInfoReturnable<Long> cir) {
        if (!Mp4AlbumView.active()) {
            return;
        }
        long live = Mp4RealState.liveDurationMillis();
        if (live > 0L) {
            cir.setReturnValue(live);
        }
    }

    /**
     * 松开进度条后开一个短窗口（见 {@link Mp4SeekHold}），窗口内不让界面
     * 用旧播放位置把进度条拉回去 —— SEEK 要等服务端重新解析直链、
     * 客户端重开音频流，新状态到位之前本机播放位置还是拖动之前那个。
     */
    /**
     * 进度条的"按下 / 松手"两端。
     *
     * <p>上游只有这里能可靠地表示"玩家真的在拖进度条"：{@code true} 设在<b>写入点击值之前</b>，
     * {@code false} 设在<b>发出 SEEK 之前</b>，正好把这一拖夹在中间。比去注入 GUI 的
     * {@code updateSliderValue} 稳得多（那里的 {@code MP4TextureHit} / {@code TexturePoint}
     * 是 {@code MP4FocusScreen} 的私有嵌套类型，跨包引用很容易踩坑）。
     *
     * <p>松手时两头比一次曲目身份：中间换了曲子就把这次 SEEK 标成作废 ——
     * 那首歌本来已经播完了，这次 SEEK 没有任何意义（见 {@code MP4FocusScreenMixin}）。
     *
     * <p>另外，拖动状态由 {@link Mp4TrackGuard} 自己维护，不读上游那个
     * {@code scrubbingProgress} —— 它有个坑：拖到一半关界面（右键/ESC）就不会被复位，
     * 之后 {@code syncFocusedProgress} 永远被挡住、进度条再也不会跟随播放。
     * 上游那个卡住的问题由 {@code MP4FocusScreenMixin} 在关界面时复位。
     */
    @Inject(method = "setScrubbingProgress", at = @At("HEAD"))
    private static void discstudio$trackScrub(boolean value, CallbackInfo ci) {
        String identity = Mp4TrackGuard.currentIdentity();
        if (value) {
            Mp4TrackGuard.beginScrub(identity);
            return;
        }
        Mp4TrackGuard.endScrub(identity);
        if (!MP4FocusState.playing()) {
            Mp4SeekHold.release();
        }
    }

    // ───────────────────── 滚动 ─────────────────────

    @Inject(method = "scrollQueue", at = @At("HEAD"), cancellable = true)
    private static void discstudio$scrollQueue(double scrollY, CallbackInfo ci) {
        if (!Mp4AlbumView.active()) {
            return;
        }
        Mp4AlbumView.scrollBy(scrollY < 0 ? 1 : -1);
        MP4FocusState.showControlsTemporarily();
        ci.cancel();
    }

    // ───────────────────── 列表点击 ─────────────────────

    @Inject(method = "selectVisibleQueueRow", at = @At("HEAD"), cancellable = true)
    private static void discstudio$selectVisibleQueueRow(int row, CallbackInfo ci) {
        if (!Mp4AlbumView.active()) {
            return;
        }
        int visibleRows = MP4FocusState.landscape()
                ? MP4FocusState.LANDSCAPE_QUEUE_VISIBLE_ROWS
                : MP4FocusState.PORTRAIT_QUEUE_VISIBLE_ROWS;
        if (row < 0 || row >= visibleRows) {
            // 命中的是面板里"行以外"的空白（上游的命中测试对这块一律回 -1：
            // 标题栏、左侧留白、最后一行下面的空隙都在这个范围里）。
            // 这里必须当成"什么都没点" —— 以前把它当"返回"，于是玩家在列表里
            // 随手点一下空白就被弹回上一级。想退回列表请点第 0 行的「< 返回 · …」。
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
            // 点专辑名 = 进专辑曲目表，而不是直接起播。
            Mp4AlbumView.open(displayRow);
            MP4FocusState.showControlsTemporarily();
            ci.cancel();
            return;
        }
        int queueIndex = Mp4AlbumView.realIndexOf(displayRow);
        if (queueIndex < 0) {
            ci.cancel();
            return;
        }
        int trackIndex = Mp4AlbumView.trackIndexOf(displayRow);
        selectedQueueIndex = queueIndex;
        mediaProgress = 0.0F;
        // 按"被点的那一行"保持可见：这一行本来就在视野里，所以滚动位置不动。
        Mp4AlbumView.ensureRowVisible(displayRow);
        if (trackIndex >= 0) {
            // 专辑内的某一首：让服务端把这张碟的曲目下标改过去（正在播就立刻起播）。
            Mp4AlbumPlayback.playTrack(queueIndex, trackIndex);
        }
        MP4FocusState.showControlsTemporarily();
        ci.cancel();
    }

    // ───────────────────── 上一曲 / 下一曲 ─────────────────────

    /*
     * 上游这两个方法只会把队列下标 ±1 —— 对"一条 = 一首"的队列是对的，
     * 但一条整专辑在它眼里也是一步，且完全无视 shuffle。
     * 队列里有专辑、或者开着随机播放时，由本模组重新算目标。
     * 列表循环（repeatMode == 2）下走到专辑首尾会<b>在本碟内</b>首尾相接，
     * 不再跨到队列里另一张碟（见 {@link Mp4AlbumPlayback#step}）。
     */

    @Inject(method = "nextTrack", at = @At("HEAD"), cancellable = true)
    private static void discstudio$nextTrack(CallbackInfo ci) {
        if (discstudio$step(1)) {
            ci.cancel();
        }
    }

    @Inject(method = "previousTrack", at = @At("HEAD"), cancellable = true)
    private static void discstudio$previousTrack(CallbackInfo ci) {
        if (discstudio$step(-1)) {
            ci.cancel();
        }
    }

    /**
     * @return 是否由本模组处理（true 表示调用方应取消上游逻辑）
     */
    @Unique
    private static boolean discstudio$step(int direction) {
        boolean shuffle = MP4FocusState.shuffle();
        if (!Mp4AlbumView.active() && !shuffle) {
            return false;
        }
        int size = MP4FocusState.queueSize();
        if (size <= 0) {
            selectedQueueIndex = 0;
            mediaProgress = 0.0F;
            MP4FocusState.showControlsTemporarily();
            return true;
        }
        ItemStack stack = Mp4RealState.currentStack();
        Mp4AlbumPlayback.Target target = Mp4AlbumPlayback.step(stack, selectedQueueIndex, direction, shuffle,
                Mp4AlbumView.albumOpen(), MP4FocusState.repeatMode());
        if (target == null) {
            return false;
        }
        selectedQueueIndex = target.queueIndex();
        mediaProgress = 0.0F;
        Mp4SeekHold.release();
        if (target.trackIndex() >= 0) {
            // 只改写这张碟"播到第几首"，真正的起播交给上游随后的 RESTART 控制包。
            Mp4AlbumPlayback.selectTrack(target.queueIndex(), target.trackIndex());
            // 专辑内：滚到即将播的那一首（列表结构是「返回行 + 曲目」，所以 +1）。
            Mp4AlbumView.ensureRowVisible(1 + target.trackIndex());
        } else {
            Mp4AlbumView.ensureVisible(selectedQueueIndex);
        }
        MP4FocusState.showControlsTemporarily();
        return true;
    }
}
