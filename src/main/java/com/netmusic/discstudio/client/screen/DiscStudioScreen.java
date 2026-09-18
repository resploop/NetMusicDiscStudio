package com.netmusic.discstudio.client.screen;

import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.gui.BlackGoldScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

/**
 * 本模组界面的共同基类：定位唱片机方块实体、统一发包口。
 * <p>
 * 直接复用 NetMusicCanPlayBili 的黑金界面基类，一是与唱片机界面观感一致，
 * 二是这一版 Minecraft 的 GUI 渲染入口已经改成
 * {@code extractRenderState(GuiGraphicsExtractor, ...)}，复用它比自绘安全得多。
 */
public abstract class DiscStudioScreen extends BlackGoldScreen {
    protected DiscStudioScreen(Component title, BlockPos pos) {
        super(title, pos);
    }

    /** 界面所属的唱片机；区块未加载或方块被拆掉时返回 {@code null}。 */
    protected ModernTurntableBlockEntity turntable() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return null;
        }
        return minecraft.level.getBlockEntity(blockPos) instanceof ModernTurntableBlockEntity turntable
                ? turntable
                : null;
    }

    /** 槽位里的唱片副本；空槽返回 {@link ItemStack#EMPTY}。 */
    protected ItemStack currentDisc() {
        ModernTurntableBlockEntity turntable = turntable();
        return turntable == null ? ItemStack.EMPTY : turntable.getDisc().copy();
    }

    protected void sendPacket(CustomPacketPayload payload) {
        if (minecraft == null || minecraft.getConnection() == null) {
            return;
        }
        minecraft.getConnection().send(payload);
    }
}
