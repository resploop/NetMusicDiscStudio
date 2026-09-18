package com.netmusic.discstudio.mixin;

import com.netmusic.discstudio.DiscStudio;
import com.netmusic.discstudio.disc.AlbumPlaylist;
import com.netmusic.discstudio.disc.DiscLoopMode;
import com.netmusic.discstudio.disc.DiscShuffle;
import com.netmusic.discstudio.disc.LoopModeHolder;
import com.netmusic.discstudio.disc.NetworkDiscs;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 让现代化唱片机会"顺着曲目表往下放"。
 * <p>
 * 上游的 {@code tick} 在一首歌放完时只有两条路：{@code repeatOne} 为真就重播当前曲，
 * 否则标记 {@code playbackCompleted} 并停下。它没有"播放列表"这个概念，所以
 * 「网络CD」放完第一首就再也不动了。
 * <p>
 * 本 Mixin 做三件事：
 * <ol>
 *   <li><b>播完自动切下一曲</b>——在 {@code tick} 里 {@code stopPlayback()} 调用点之后挂钩子
 *       （那个调用点在 tick 内唯一，且正好落在"放完了"这个分支里）。如果槽内是带曲目表的
 *       网络CD、且模式不是「单曲循环」，就按当前模式（顺序 / 列表 / 随机）算出下一首并重新开播。</li>
 *   <li><b>把「单曲循环」按钮升级成多态轮转</b>——上游界面按钮发的
 *       {@code TOGGLE_REPEAT_ONE} 落到 {@code toggleRepeatOne()}，这里改成轮转
 *       {@link DiscLoopMode}（顺序 → 单曲 → 列表 → 随机），并把结果同步回上游那个
 *       {@code repeatOne} 布尔量，让"重播当前曲"这件事仍然走上游自己的代码。</li>
 *   <li><b>持久化 + 同步</b>——模式写进 {@code saveAdditional}，因此既落盘也会随
 *       方块实体更新包发给客户端，界面上才能显示当前模式。</li>
 * </ol>
 * <p>
 * 关于「播完提取」：抽盘资格由上游 {@code canAutomationExtract()} 判定
 * （{@code 自由提取 || playbackCompleted}）。顺序播放在放完中间某一首时会立刻切下一首，
 * 而切换走的是 {@code setDisc()}——它会顺手把 {@code playbackCompleted} 清掉，
 * 因此中间曲目结束时不会出现一个"可以抽盘"的时间窗；只有整张表最后一首放完、
 * 这里直接 {@code return} 不动它，{@code playbackCompleted} 才保持为真。
 */
@Mixin(ModernTurntableBlockEntity.class)
public abstract class ModernTurntableBlockEntityMixin implements LoopModeHolder {

    /** 上游的"单曲循环"布尔量，本 Mixin 让它成为 {@link DiscLoopMode#REPEAT_ONE} 的镜像。 */
    @Shadow
    private boolean repeatOne;

    @Unique
    private DiscLoopMode discstudio$loopMode = DiscLoopMode.SEQUENTIAL;

    @Override
    public DiscLoopMode discstudio$loopMode() {
        return discstudio$loopMode;
    }

    @Override
    public void discstudio$setLoopMode(DiscLoopMode mode) {
        if (mode == null || mode == discstudio$loopMode) {
            return;
        }
        discstudio$loopMode = mode;
        // 上游 tick 判断"要不要重播当前曲"只看 repeatOne，这里让它与模式保持一致。
        repeatOne = mode.repeatsCurrentTrack();
        discstudio$self().markDirty();
    }

    // ───────────────────── 1. 播完自动切下一曲 ─────────────────────

    @Inject(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/zhongbai233/net_music_can_play_bili/blockentity/ModernTurntableBlockEntity;stopPlayback()V",
                    shift = At.Shift.AFTER))
    private static void discstudio$advancePlaylist(Level level, BlockPos pos, BlockState state,
                                                   ModernTurntableBlockEntity turntable, CallbackInfo ci) {
        // 只有服务端跑 tick 逻辑；顺带挡掉 {@code stopPlayback} 之外的调用点（tick 内没有别的）。
        if (!(level instanceof ServerLevel)) {
            return;
        }
        ItemStack disc = turntable.getDisc();
        AlbumPlaylist playlist = NetworkDiscs.playlist(disc);
        if (playlist == null || playlist.isEmpty()) {
            // 单曲唱片（含可擦写网络唱片）：保持上游"放完就停"的行为。
            return;
        }

        DiscLoopMode mode = ((LoopModeHolder) turntable).discstudio$loopMode();
        int size = playlist.size();
        int current = playlist.selectedIndex();
        // -1 表示"这次真的该停了"：不动 playbackCompleted，播完提取在这里才放行。
        int next = switch (mode) {
            case SEQUENTIAL -> current + 1 < size ? current + 1 : -1;
            case REPEAT_ALL -> (current + 1) % size;
            case SHUFFLE -> size > 1 ? DiscShuffle.indexOtherThan(level.getRandom(), size, current) : -1;
            // 单曲循环：上游在 repeatOne 为真时走 restartForRepeat，压根不会调 stopPlayback，
            // 因此这里不会被执行；写上只是为了让 switch 穷尽。
            case REPEAT_ONE -> -1;
        };
        if (next < 0) {
            // 顺序播放放到最后一首、或随机播放但表里只有一首：停在原地等被抽走。
            return;
        }

        AlbumPlaylist advanced = playlist.select(next);
        ItemStack updated = disc.copy();
        NetworkDiscs.selectTrack(updated, advanced);
        if (NetworkDiscs.playlist(updated) == null) {
            // 理论上不会发生；真发生了说明写回把曲目表弄丢了，宁可不切歌也不要放空。
            DiscStudio.LOGGER.warn("网络CD 切曲后曲目表丢失，已放弃切歌: {}", playlist.displayTitle());
            return;
        }
        // setDisc 内部会 stopPlayback + markDirty（顺带清掉 playbackCompleted），
        // 曲目表与 netmusic:song_info 一起同步给客户端。
        turntable.setDisc(updated);
        DiscStudio.LOGGER.debug("网络CD 自动切曲: {} -> {}/{} {}", playlist.displayTitle(), next + 1, size,
                advanced.currentTrack() == null ? "?" : advanced.currentTrack().songName);
        turntable.startFromDisc();
    }

    // ───────────────── 2. 「单曲循环」按钮升级为多态轮转 ─────────────────

    @Inject(method = "toggleRepeatOne", at = @At("HEAD"), cancellable = true)
    private void discstudio$cycleLoopMode(CallbackInfo ci) {
        ModernTurntableBlockEntity self = discstudio$self();
        discstudio$setLoopMode(discstudio$loopMode.next(NetworkDiscs.hasPlaylist(self.getDisc())));
        // 上游的"取反"语义已由 setLoopMode 覆盖，取消原实现。
        ci.cancel();
    }

    // ───────────────────── 3. 持久化与客户端同步 ─────────────────────

    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void discstudio$saveLoopMode(ValueOutput output, CallbackInfo ci) {
        output.putString(LoopModeHolder.DISC_STUDIO_LOOP_MODE, discstudio$loopMode.serializedName());
    }

    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void discstudio$loadLoopMode(ValueInput input, CallbackInfo ci) {
        String stored = input.getStringOr(LoopModeHolder.DISC_STUDIO_LOOP_MODE, "");
        if (stored.isEmpty()) {
            // 老存档 / 别人的唱片机：没有本模组的标签，就从上游的 repeatOne 反推一次。
            discstudio$loopMode = repeatOne ? DiscLoopMode.REPEAT_ONE : DiscLoopMode.SEQUENTIAL;
            return;
        }
        discstudio$loopMode = DiscLoopMode.byName(stored);
        repeatOne = discstudio$loopMode.repeatsCurrentTrack();
    }

    @Unique
    private ModernTurntableBlockEntity discstudio$self() {
        return (ModernTurntableBlockEntity) (Object) this;
    }
}
