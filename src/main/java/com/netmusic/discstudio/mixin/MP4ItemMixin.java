package com.netmusic.discstudio.mixin;

import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * MP4 队列：一张「网络CD」与普通唱片一样，<b>只占一条</b>队列项。
 *
 * <p>上游 MP4 的队列是 {@code List<ItemStack>}，一条 = 一张碟。网络CD 用它自带的
 * {@code album_playlist} 组件记住"当前播到第几首"，所以整张专辑天然就是一条，
 * <b>不需要</b>任何摊平处理 —— 这正是本模组要的模型：18 条的队列上限 = 能放 18 张碟。
 *
 * <p>（早期版本把专辑摊平成 N 条塞进队列，导致"放一张 18 首的专辑就把队列占满、
 * 再也放不进别的碟"。那套逻辑已删除。）
 *
 * <p>这里只补一件事：队列真的满 18 条时，上游 {@code addDisc} 会<b>静默失败</b>，
 * 玩家会以为物品坏了。所以失败时补一条提示，明确告诉他"满了"。
 */
@Mixin(MP4Item.class)
public class MP4ItemMixin {

    /** 从「MP4 叠到其它槽位」这条路径入库：失败且是满员时给提示。 */
    @Inject(method = "overrideStackedOnOther", at = @At("RETURN"))
    private void discstudio$hintFullFromSlot(ItemStack mp4Stack, Slot slot, ClickAction action, Player player,
                                             CallbackInfoReturnable<Boolean> cir) {
        if (action == ClickAction.PRIMARY && Boolean.FALSE.equals(cir.getReturnValue())) {
            discstudio$hintQueueFull(player, mp4Stack, slot.getItem());
        }
    }

    /** 从「把碟叠到 MP4 上」这条路径入库：同上。 */
    @Inject(method = "overrideOtherStackedOnMe", at = @At("RETURN"))
    private void discstudio$hintFullFromCarried(ItemStack mp4Stack, ItemStack carriedStack, Slot slot,
                                                ClickAction action, Player player, SlotAccess carriedAccess,
                                                CallbackInfoReturnable<Boolean> cir) {
        if (action == ClickAction.PRIMARY && Boolean.FALSE.equals(cir.getReturnValue())) {
            discstudio$hintQueueFull(player, mp4Stack, carriedStack);
        }
    }

    /**
     * 只有当"放不进去"确实是因为队列满时才提示。
     * 别的失败原因（放的压根不是唱片等）不该被误报。
     */
    @Unique
    private static void discstudio$hintQueueFull(Player player, ItemStack mp4Stack, ItemStack discStack) {
        if (!(player instanceof ServerPlayer serverPlayer) || !MP4Item.isNetMusicDisc(discStack)) {
            return;
        }
        if (MP4Item.readQueue(mp4Stack).size() < MP4Item.MAX_QUEUE_SIZE) {
            return;
        }
        // 第二参数 true = 走物品栏上方的浮层提示（overlay），不占聊天框。
        serverPlayer.sendSystemMessage(Component
                .translatable("message.netmusic_disc_studio.mp4_queue_full", MP4Item.MAX_QUEUE_SIZE)
                .withStyle(ChatFormatting.YELLOW), true);
    }
}
