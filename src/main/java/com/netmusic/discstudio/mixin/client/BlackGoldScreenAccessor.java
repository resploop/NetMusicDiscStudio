package com.netmusic.discstudio.mixin.client;

import com.zhongbai233.net_music_can_play_bili.gui.BlackGoldScreen;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@code BlackGoldScreen#blockPos} 是 protected 字段，用 Accessor 读出界面绑定的方块坐标。
 * <p>
 * 用 Accessor 而不是在唱片机界面的 Mixin 里 {@code @Shadow}，是因为该字段定义在父类上。
 */
@Mixin(BlackGoldScreen.class)
public interface BlackGoldScreenAccessor {
    @Accessor("blockPos")
    BlockPos discstudio$blockPos();
}
