package com.netmusic.discstudio.client.album;

import com.zhongbai233.net_music_can_play_bili.client.MP4FocusState;
import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlayback;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * 「当前正在用的那台 MP4」的定位口。
 * <p>
 * 与上游 {@code MP4QueueCompletionPolicy#sendControl} /
 * {@code MP4FocusScreen#sendPlayback} 同一套定位方式：界面开着就用手上那把，
 * 否则在背包里找一个。客户端要读队列或设备 ID 时统一走这里，避免各处重复写这段逻辑。
 */
public final class Mp4RealState {
    private Mp4RealState() {
    }

    /** 当前正在使用的 MP4；取不到返回空栈。 */
    public static ItemStack currentStack() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null) {
            return ItemStack.EMPTY;
        }
        InteractionHand hand = MP4FocusState.active() ? MP4FocusState.hand() : InteractionHand.MAIN_HAND;
        ItemStack held = minecraft.player.getItemInHand(hand);
        if (held.getItem() instanceof MP4Item) {
            return held;
        }
        ItemStack any = MP4Item.findAnyInInventory(minecraft.player);
        return any == null ? ItemStack.EMPTY : any;
    }

    /**
     * 这台 MP4 <b>此刻真正在播</b>的那首曲子有多长（毫秒）；拿不到返回 0。
     *
     * <p>用途：拖进度条时要把「拖到百分之几」换成绝对毫秒，这个基数必须是
     * <b>真实在播曲目</b>的时长。
     *
     * <p>为什么不能直接用 {@code MP4FocusState#selectedTrackDurationMillis()}：
     * 它读的是客户端缓存的队列表时长（{@code MP4Client.DEVICE_QUEUES} → {@code queueDurations}），
     * 而这份缓存只在服务端推送状态时刷新。本模组的网络CD换曲改的是物品 NBT，
     * 缓存会滞后若干刻 —— 基数一旦偏大，拖到一半就可能算出超过真实曲长的目标位置，
     * 服务端把它夹到「结尾前 50 毫秒」，曲子立刻"播完"，听感就是
     * <b>进度条动了但没声音</b>。
     *
     * <p>而本机播放注册表里的时长来自服务端对<b>当前曲目</b>直链的真实解析结果，
     * 与正在播的东西严格一致，才是可靠的基数。
     */
    public static long liveDurationMillis() {
        ItemStack stack = currentStack();
        if (!(stack.getItem() instanceof MP4Item)) {
            return 0L;
        }
        UUID deviceId = MP4Item.readDeviceId(stack);
        if (deviceId == null) {
            return 0L;
        }
        return Math.max(0L, ClientMediaPlayback.durationMillis(deviceId));
    }
}
