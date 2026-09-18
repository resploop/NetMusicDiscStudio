package com.netmusic.discstudio.client;

import com.netmusic.discstudio.client.screen.AlbumTrackPickerScreen;
import com.netmusic.discstudio.client.screen.SwapBvScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * 客户端界面入口。
 * <p>
 * 两个界面都只在唱片机界面里由玩家主动点开，插入唱片时不会自动弹出：
 * <ul>
 *   <li>{@link #openTrackPicker} —— 「网络CD」的只读选曲面板；</li>
 *   <li>{@link #openSwapBv} —— 「可擦写网络唱片」的换 BV 面板。</li>
 * </ul>
 * 网络CD 的曲目表写入只在唱片刻录机里完成，这里不做。
 */
public final class DiscStudioScreens {
    private DiscStudioScreens() {
    }

    public static void openTrackPicker(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        minecraft.setScreen(new AlbumTrackPickerScreen(pos));
    }

    public static void openSwapBv(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        minecraft.setScreen(new SwapBvScreen(pos));
    }
}
