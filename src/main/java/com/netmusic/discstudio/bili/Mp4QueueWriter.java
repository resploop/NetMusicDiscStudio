package com.netmusic.discstudio.bili;

import com.zhongbai233.net_music_can_play_bili.bili.BiliSongInfoSanitizer;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.List;

/**
 * MP4 队列的写入口。
 * <p>
 * 上游 {@code MP4Item#writeQueue} 是 {@code private static}，本模组要"改写队列"
 * （专辑内切曲、服务端控制播放时写回选中项）就必须能写回去。
 * <p>
 * 这里<b>不</b>用 {@code @Invoker} 去撬私有方法，而是用公开 API 原样复刻它的实现：
 * 队列就存在物品的 {@code minecraft:custom_data} 里，结构简单且稳定，
 * 复刻比多挂一个 mixin 更不容易在游戏启动阶段炸掉。
 * <p>
 * <b>下面两个 NBT 键必须与上游 {@code MP4Item} 保持一致</b>（那边也是私有常量）：
 * {@code mp4_queue}（队列列表）与 {@code stack}（每条目的物品）。
 */
public final class Mp4QueueWriter {
    private Mp4QueueWriter() {
    }

    private static final String DATA_QUEUE = "mp4_queue";
    private static final String DATA_QUEUE_STACK = "stack";

    /**
     * 覆盖写入 MP4 的播放队列。
     * <p>
     * 与上游 {@code writeQueue} 逐行等价：非唱片条目被丢弃、每条都过一遍
     * {@link BiliSongInfoSanitizer#sanitizeDisc}，队列长度不做裁剪
     * （调用方需要自己守住 {@link MP4Item#MAX_QUEUE_SIZE}）。
     */
    public static void write(ItemStack mp4Stack, List<ItemStack> queue) {
        ListTag listTag = new ListTag();
        for (ItemStack disc : queue) {
            if (!MP4Item.isNetMusicDisc(disc)) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.store(DATA_QUEUE_STACK, ItemStack.OPTIONAL_CODEC, BiliSongInfoSanitizer.sanitizeDisc(disc));
            listTag.add(entry);
        }
        mp4Stack.update(DataComponents.CUSTOM_DATA, CustomData.EMPTY,
                existing -> existing.update(tag -> tag.put(DATA_QUEUE, listTag)));
    }
}
