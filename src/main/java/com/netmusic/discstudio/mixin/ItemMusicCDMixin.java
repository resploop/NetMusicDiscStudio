package com.netmusic.discstudio.mixin;

import com.github.tartaricacid.netmusic.init.InitDataComponent;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.netmusic.discstudio.disc.NetworkDiscs;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让 NetMusic 的 {@link ItemMusicCD} 认识本模组的两件唱片物品。
 * <p>
 * {@code ItemMusicCD#getSongInfo} / {@code setSongInfo} 内部把物品 ID 写死成
 * {@code netmusic:music_cd}，而 NetMusicCanPlayBili 的现代化唱片机、自动化适配器
 * （{@code ModernTurntableDiscHandler#isValid}）以及 {@code BiliSongInfoSanitizer}
 * 全部通过这两个方法判断"这是不是一张唱片"。
 * <p>
 * 因此只要在这里把本模组物品接上，上游所有既有链路（放盘校验、播放解析、破坏掉落、
 * 红石控制、比较器输出）都会自然生效，不需要改动唱片机本身。
 * <p>
 * 本模组物品与 {@code music_cd} 共用同一个 {@code netmusic:song_info} 数据组件，
 * 因为数据组件本身与物品无关。
 */
@Mixin(ItemMusicCD.class)
public class ItemMusicCDMixin {
    @Inject(method = "getSongInfo", at = @At("HEAD"), cancellable = true)
    private static void discstudio$getSongInfo(ItemStack stack, CallbackInfoReturnable<ItemMusicCD.SongInfo> cir) {
        if (!NetworkDiscs.isNetworkDisc(stack)) {
            return;
        }
        // 空白唱片返回 null，与 music_cd 空盘的语义保持一致。
        cir.setReturnValue(NetworkDiscs.songInfo(stack));
    }

    @Inject(method = "setSongInfo", at = @At("HEAD"), cancellable = true)
    private static void discstudio$setSongInfo(ItemMusicCD.SongInfo info, ItemStack stack,
                                               CallbackInfoReturnable<ItemStack> cir) {
        if (!NetworkDiscs.isNetworkDisc(stack)) {
            return;
        }
        stack.set(InitDataComponent.SONG_INFO.get(), info);
        cir.setReturnValue(stack);
    }
}
