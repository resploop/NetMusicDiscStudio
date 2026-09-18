package com.netmusic.discstudio.mixin;

import com.netmusic.discstudio.DiscStudio;
import com.netmusic.discstudio.bili.Mp4QueueWriter;
import com.netmusic.discstudio.disc.AlbumGroups;
import com.netmusic.discstudio.disc.AlbumPlaylist;
import com.netmusic.discstudio.disc.NetworkDiscs;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * 让 MP4 认识「网络CD」这种"一张碟装一整张专辑"的物品。
 * <p>
 * 上游 MP4 的播放队列是 {@code List<ItemStack>}，<b>一条 = 一首曲子</b>，
 * 没有专辑 / 分组 / 子列表的概念 —— 它在 {@code addDisc} 里把整张碟当成一条塞进队列，
 * 于是网络CD放进去只能听"当前选中那一首"，剩下的歌永远轮不到。
 * <p>
 * 本 Mixin 的做法是<b>在入库时把专辑摊平</b>：
 *
 * <ol>
 *   <li><b>{@code addDisc}</b> —— 如果放进来的是带曲目表的网络CD，就把它复制成 N 份，
 *       第 k 份的 {@link AlbumPlaylist#selectedIndex()} 改成 k 后再逐条入队。
 *       于是队列里出现连续 N 条形如"同一张专辑的第 k 首"的条目。</li>
 *   <li><b>{@code removeQueueDisc}</b> —— 取出时如果命中的条目属于某个专辑组，
 *       就整组一起取走，并还给玩家一张曲目表完整的网络CD，
 *       避免只抽走一首、剩下的散落在队列里。</li>
 * </ol>
 *
 * <p>为什么是"摊平"而不是"一条代表一张专辑"：摊平之后，
 * MP4 自己的<b>单曲循环 / 顺序 / 列表循环</b>、进度推进、断线恢复、队列镜像同步
 * 全部作用在这些条目上，<b>上游一行都不用改</b>，我们也不需要去碰
 * {@code MP4PlaybackQueueController} 之类包私有、签名里带包私有类型的类。
 * 代价是受上游 {@link MP4Item#MAX_QUEUE_SIZE}（18）限制，
 * 一张碟最多占 18 条 —— 超出的部分会在入库时截断并打日志。
 *
 * <p>客户端侧的「折叠成一行 [专辑] 名称 + 点进去展开曲目」由
 * {@code com.netmusic.discstudio.mixin.client.MP4FocusStateMixin} 负责。
 */
@Mixin(MP4Item.class)
public class MP4ItemMixin {

    // ───────────────────── 入库：把专辑摊平成 N 条 ─────────────────────

    @Inject(method = "addDisc", at = @At("HEAD"), cancellable = true)
    private static void discstudio$expandAlbum(ItemStack mp4Stack, ItemStack discStack,
                                               CallbackInfoReturnable<Boolean> cir) {
        AlbumPlaylist playlist = NetworkDiscs.playlist(discStack);
        if (playlist == null || playlist.size() <= 1) {
            // 不是网络CD（可擦写网络唱片、上游 music_cd 等）或只有一首：走上游原逻辑。
            return;
        }
        cir.setReturnValue(discstudio$addExpanded(mp4Stack, discStack, playlist));
    }

    /**
     * 把 {@code playlist} 的每一首作为独立条目追加进 MP4 队列。
     *
     * @return 是否成功写入至少一条（成功则照上游语义消耗掉一张碟）
     */
    @Unique
    private static boolean discstudio$addExpanded(ItemStack mp4Stack, ItemStack discStack, AlbumPlaylist playlist) {
        if (mp4Stack.isEmpty() || !(mp4Stack.getItem() instanceof MP4Item)) {
            return false;
        }
        List<ItemStack> queue = new ArrayList<>(MP4Item.readQueue(mp4Stack));
        int room = MP4Item.MAX_QUEUE_SIZE - queue.size();
        if (room <= 0) {
            return false;
        }
        int take = Math.min(playlist.size(), room);
        int added = 0;
        for (int track = 0; track < take; track++) {
            // selectTrack 会原地改这份副本：写入目标下标，并把 netmusic:song_info 同步成那一首。
            ItemStack entry = discStack.copyWithCount(1);
            NetworkDiscs.selectTrack(entry, playlist.select(track));
            if (!NetworkDiscs.hasPlaylist(entry)) {
                // 理论上不会发生；真丢了曲目表就宁可少一条，也不要往队列里塞放不出声的东西。
                continue;
            }
            queue.add(entry);
            added++;
        }
        if (added <= 0) {
            return false;
        }
        Mp4QueueWriter.write(mp4Stack, queue);
        discStack.shrink(1);
        if (added < playlist.size()) {
            DiscStudio.LOGGER.warn("MP4 队列空间不足，专辑《{}》只写入前 {}/{} 首",
                    playlist.displayTitle(), added, playlist.size());
        }
        return true;
    }

    // ───────────────────── 取出：整张专辑一起走 ─────────────────────

    @Inject(method = "removeQueueDisc", at = @At("HEAD"), cancellable = true)
    private static void discstudio$removeWholeAlbum(ItemStack mp4Stack, int preferredIndex,
                                                    CallbackInfoReturnable<ItemStack> cir) {
        List<ItemStack> queue = new ArrayList<>(MP4Item.readQueue(mp4Stack));
        if (queue.isEmpty()) {
            return;
        }
        // 与上游 removeQueueDisc 同一套下标解析：-1 / 越界都取最后一条。
        int index = preferredIndex >= 0 && preferredIndex < queue.size() ? preferredIndex : queue.size() - 1;
        AlbumGroups.Group group = AlbumGroups.groupOf(AlbumGroups.group(queue), index);
        if (group == null || !group.isAlbum()) {
            return;   // 单曲条目：走上游原逻辑
        }
        // 还给玩家的是一张完整专辑（曲目表原样保留，只是选中下标回到第一条）。
        ItemStack disc = queue.get(group.startIndex()).copyWithCount(1);
        for (int i = group.endIndex(); i >= group.startIndex(); i--) {
            queue.remove(i);
        }
        Mp4QueueWriter.write(mp4Stack, queue);
        DiscStudio.LOGGER.debug("从 MP4 整组取出专辑《{}》（{} 首）", group.title(), group.count());
        cir.setReturnValue(disc);
    }
}
