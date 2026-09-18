package com.netmusic.discstudio.mixin;

import com.github.tartaricacid.netmusic.inventory.io.CDOutput;
import com.netmusic.discstudio.disc.NetworkDiscs;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让唱片刻录机的输出槽接受本模组的两件唱片。
 * <p>
 * 刻录完的碟会被 {@code CDBurnerMenu#setSongInfo} 从输入槽搬到输出槽，
 * 输出槽同样写死了 {@code netmusic:music_cd}，所以这里也要放开，
 * 否则刻好的碟搬不过去（等于刻录失败）。
 */
@Mixin(CDOutput.class)
public abstract class CDOutputMixin {
    @Inject(method = "isValid", at = @At("HEAD"), cancellable = true)
    private void discstudio$allowNetworkDiscs(ItemResource resource, CallbackInfoReturnable<Boolean> cir) {
        ItemStack stack = resource.toStack();
        if (NetworkDiscs.isNetworkDisc(stack)) {
            cir.setReturnValue(true);
        }
    }
}
