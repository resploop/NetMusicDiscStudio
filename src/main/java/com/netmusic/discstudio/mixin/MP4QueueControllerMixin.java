package com.netmusic.discstudio.mixin;

import com.netmusic.discstudio.bili.Mp4AlbumAdvance;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 「一首放完了接下来放什么」——把专辑的<b>专辑内推进</b>接到服务端真正的决策点上。
 *
 * <p>上游 {@code MP4PlaybackQueueController#tryAdvanceQueue} 只会把队列下标 ±1，
 * 每条都当成一首歌。但本模组里一条队列项是一整张网络CD，所以要先问一句
 * "这张碟里还有下一首吗"，有就在同一条目上换曲（见 {@link Mp4AlbumAdvance}）。
 *
 * <p>第三个参数是 {@code MP4PlaybackSyncManager.Session} —— 一个包私有的嵌套 record，
 * 我们的代码引用不到它的类型，但我们本来也不需要用它的字段（设备 ID 从物品上读、
 * 当前下标从设备状态里读就够）。所以标 {@code @Coerce} 让 Mixin 允许用父类型接收：
 * 少了这个注解，Mixin 会以 "Handler signature ... Expected signature ..." 直接开不了游戏。
 */
@Mixin(targets = "com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackQueueController")
public class MP4QueueControllerMixin {

    @Inject(method = "tryAdvanceQueue", at = @At("HEAD"), cancellable = true)
    private void discstudio$advanceAlbumTrack(ServerLevel level, ItemStack stack, @Coerce Object session,
            CallbackInfoReturnable<Boolean> cir) {
        if (Mp4AlbumAdvance.handleCompletion(level, stack)) {
            cir.setReturnValue(true);
        }
    }
}
