package com.netmusic.discstudio.mixin;

import com.github.tartaricacid.netmusic.inventory.io.CDInput;
import com.netmusic.discstudio.disc.NetworkDiscs;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让唱片刻录机的输入槽接受本模组的两件唱片。
 * <p>
 * 上游的 {@code CDInput} 把物品白名单写死成 {@code netmusic:music_cd}，
 * 不改这里的话唱片根本放不进去。
 */
@Mixin(CDInput.class)
public abstract class CDInputMixin {
    @Inject(method = "isValid", at = @At("HEAD"), cancellable = true)
    private void discstudio$allowNetworkDiscs(ItemResource resource, CallbackInfoReturnable<Boolean> cir) {
        ItemStack stack = resource.toStack();
        if (NetworkDiscs.isNetworkDisc(stack)) {
            cir.setReturnValue(true);
        }
    }
}
