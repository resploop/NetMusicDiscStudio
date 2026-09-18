package com.netmusic.discstudio.mixin;

import com.netmusic.discstudio.disc.NetworkDiscs;
import com.zhongbai233.net_music_can_play_bili.block.ModernTurntableBlock;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让「可擦写网络唱片」「网络CD」能像普通唱片一样放进现代化唱片机。
 * <p>
 * 上游 {@code ModernTurntableBlock#useItemOn} 的判定是
 * {@code ItemMusicCD.getSongInfo(stack) != null}——空白唱片会被当成"不是 CD"
 * 而直接拒绝放入，因此这里在 HEAD 处提前接管：
 * <ul>
 *   <li>潜行右键、副手右键：原样交还上游（潜行右键开的是唱片机自己的界面）；</li>
 *   <li>槽位已有唱片：按上游语义弹出唱片；</li>
 *   <li>槽位为空：放入唱片并直接播放。空白唱片播不了，给一句提示让玩家去刻录机。</li>
 * </ul>
 * 唱片机不再弹出任何写入界面——刻录统一在唱片刻录机完成。
 */
@Mixin(ModernTurntableBlock.class)
public class ModernTurntableBlockMixin {
    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void discstudio$useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                      Player player, InteractionHand hand, BlockHitResult hitResult,
                                      CallbackInfoReturnable<InteractionResult> cir) {
        if (hand == InteractionHand.OFF_HAND || player.isShiftKeyDown()) {
            return;
        }
        if (!NetworkDiscs.isNetworkDisc(stack)) {
            return;
        }
        if (!(level.getBlockEntity(pos) instanceof ModernTurntableBlockEntity turntable)) {
            return;
        }
        if (level.isClientSide()) {
            cir.setReturnValue(InteractionResult.SUCCESS);
            return;
        }

        if (turntable.hasDisc()) {
            ItemStack removed = turntable.removeDisc();
            if (!removed.isEmpty()) {
                Block.popResource(level, pos, removed);
            }
            cir.setReturnValue(InteractionResult.SUCCESS);
            return;
        }

        ItemStack placed = stack.copyWithCount(1);
        // setDisc 内部已经 stopPlayback() + markDirty()，槽位变化会自动同步给客户端。
        turntable.setDisc(placed);
        if (!player.isCreative()) {
            stack.shrink(1);
        }
        if (NetworkDiscs.songInfo(placed) != null && player instanceof ServerPlayer serverPlayer) {
            turntable.startFromDisc(serverPlayer);
        } else if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.sendSystemMessage(Component
                    .translatable("message.netmusic_disc_studio.blank_disc_needs_burning")
                    .withStyle(ChatFormatting.YELLOW));
        }
        cir.setReturnValue(InteractionResult.SUCCESS);
    }
}
