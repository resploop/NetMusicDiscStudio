package com.netmusic.discstudio.client.album;

import com.zhongbai233.net_music_can_play_bili.client.MP4Client;
import com.zhongbai233.net_music_can_play_bili.client.MP4FocusState;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * MP4 的「真实」队列状态的读数口。
 * <p>
 * 本模组为了让列表能折叠展示专辑，把 {@link MP4FocusState#queueSize()} /
 * {@link MP4FocusState#selectedQueueIndex()} 暂时改成了<b>显示行空间</b>的值
 * （一行 = 一张专辑，而不是一条队列项）。
 * <p>
 * 但上游有几处必须拿到<b>真实</b>的值，否则行为会错：
 * <ul>
 *   <li>{@code MP4QueueCompletionPolicy#onCompleted} —— 判断"是不是最后一首"；</li>
 *   <li>它内部的 {@code sendControl} —— 会把下标发给服务端换源，绝不能是显示下标。</li>
 * </ul>
 * <p>
 * 这里直接从物品与客户端设备状态缓存里取真实值：{@code MP4Item.queueSize(stack)}
 * 是真实队列长度，{@code MP4Client.cachedStateFor(stack)} 是服务端镜像下来的真实状态
 * （该方法不会返回 null，取不到时回落成 {@code State.DEFAULT}）。
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
        // 与上游 MP4QueueCompletionPolicy#sendControl 同一套定位方式：
        // 界面开着就用手上那把，否则在背包里找一个。
        InteractionHand hand = MP4FocusState.active() ? MP4FocusState.hand() : InteractionHand.MAIN_HAND;
        ItemStack held = minecraft.player.getItemInHand(hand);
        if (held.getItem() instanceof MP4Item) {
            return held;
        }
        ItemStack any = MP4Item.findAnyInInventory(minecraft.player);
        return any == null ? ItemStack.EMPTY : any;
    }

    /** 真实队列长度；定位不到 MP4 时回落到上游取值。 */
    public static int queueSize() {
        ItemStack stack = currentStack();
        if (!(stack.getItem() instanceof MP4Item)) {
            return MP4FocusState.queueSize();
        }
        return MP4Item.queueSize(stack);
    }

    /** 真实选中下标；定位不到 MP4 时回落到上游取值。 */
    public static int selectedQueueIndex() {
        ItemStack stack = currentStack();
        if (!(stack.getItem() instanceof MP4Item)) {
            return MP4FocusState.selectedQueueIndex();
        }
        return MP4Client.cachedStateFor(stack).selectedQueueIndex();
    }
}
