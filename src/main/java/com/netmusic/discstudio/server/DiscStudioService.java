package com.netmusic.discstudio.server;

import com.github.tartaricacid.netmusic.inventory.CDBurnerMenu;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.netmusic.discstudio.DiscStudio;
import com.netmusic.discstudio.api.NetEaseAlbumApi;
import com.netmusic.discstudio.api.NetEaseReference;
import com.netmusic.discstudio.bili.Mp4AlbumAdvance;
import com.netmusic.discstudio.bili.Mp4AlbumScope;
import com.netmusic.discstudio.bili.Mp4QueueWriter;
import com.netmusic.discstudio.disc.AlbumPlaylist;
import com.netmusic.discstudio.disc.DiscLoopMode;
import com.netmusic.discstudio.disc.DiscShuffle;
import com.netmusic.discstudio.disc.LoopModeHolder;
import com.netmusic.discstudio.disc.NetworkDiscs;
import com.netmusic.discstudio.network.DiscStudioTrackPacket;
import com.netmusic.discstudio.network.Mp4AlbumScopePacket;
import com.netmusic.discstudio.network.Mp4AlbumTrackPacket;
import com.netmusic.discstudio.network.SwapBvPacket;
import com.zhongbai233.net_music_can_play_bili.bili.BiliAudioResolver;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import com.zhongbai233.net_music_can_play_bili.network.MP4DeviceStateStore;
import com.zhongbai233.net_music_can_play_bili.network.MP4PlaybackControlPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.transfer.item.ItemResource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 服务端实现：刻录机写盘 + 唱片机切歌。
 * <p>
 * 写入只发生在唱片刻录机；现代化唱片机只负责播放与选曲。
 * 网易云请求这类阻塞 HTTP 一律丢到工作线程，结果再回到服务端主线程落地。
 */
public final class DiscStudioService {
    /** 与上游唱片机控制包保持一致的操作距离限制，避免远程操作别人的唱片机。 */
    private static final double MAX_OPERATION_DISTANCE_SQR = 64.0D;

    private static final ExecutorService FETCH_EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "DiscStudio-Fetch");
        thread.setDaemon(true);
        return thread;
    });

    private DiscStudioService() {
    }

    // ─────────────────────────── 刻录机：写盘 ───────────────────────────

    /**
     * 把输入槽里的「网络CD」刻成整张专辑/歌单。
     *
     * @param player 必须正开着唱片刻录机
     * @param input  玩家输入的专辑/歌单链接
     */
    public static void burnAlbum(ServerPlayer player, String input) {
        if (!(player.containerMenu instanceof CDBurnerMenu menu)) {
            message(player, "message.netmusic_disc_studio.need_burner");
            return;
        }
        ItemStack disc = menu.getInput().getResource(0).toStack();
        if (!NetworkDiscs.isAlbumCd(disc)) {
            message(player, "message.netmusic_disc_studio.burner_need_album_cd");
            return;
        }
        ItemMusicCD.SongInfo existing = NetworkDiscs.songInfo(disc);
        if (existing != null && existing.readOnly) {
            message(player, "message.netmusic_disc_studio.disc_read_only");
            return;
        }
        Optional<NetEaseReference> reference = NetEaseReference.parseStrict(input);
        if (reference.isEmpty()) {
            message(player, "message.netmusic_disc_studio.bad_album_input");
            return;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }

        FETCH_EXECUTOR.execute(() -> {
            try {
                AlbumPlaylist playlist = NetEaseAlbumApi.fetch(reference.get());
                onMain(level, () -> applyAlbumBurn(player, menu, playlist, input));
            } catch (Exception exception) {
                DiscStudio.LOGGER.warn("刻录网络CD失败: {}", input, exception);
                String reason = exception.getMessage() == null ? exception.getClass().getSimpleName()
                        : exception.getMessage();
                onMain(level, () -> message(player, "message.netmusic_disc_studio.write_failed", reason));
            }
        });
    }

    private static void applyAlbumBurn(ServerPlayer player, CDBurnerMenu menu, AlbumPlaylist playlist,
                                       String input) {
        // 解析是异步的，期间玩家可能已经关掉刻录机或换了一张碟。
        if (player.containerMenu != menu) {
            message(player, "message.netmusic_disc_studio.burner_closed");
            return;
        }
        ItemStack disc = menu.getInput().getResource(0).toStack();
        if (!NetworkDiscs.isAlbumCd(disc)) {
            message(player, "message.netmusic_disc_studio.disc_changed");
            return;
        }

        // 先把曲目表写进碟本身，再交给上游的搬运动作（输入槽 → 输出槽）。
        NetworkDiscs.writeAlbum(disc, playlist, input);
        menu.getInput().set(0, ItemResource.of(disc), disc.getCount());
        menu.setSongInfo(playlist.currentTrack());

        String title = playlist.title().isBlank() ? playlist.sourceId() : playlist.title();
        message(player, "message.netmusic_disc_studio.album_burned", title, playlist.size());
        if (playlist.truncated()) {
            message(player, "message.netmusic_disc_studio.album_truncated", AlbumPlaylist.MAX_TRACKS);
        }
    }

    // ───────────────────── 唱片机：换 BV（仅可擦写唱片） ─────────────────────

    /**
     * 就地改写唱片机槽位里「可擦写网络唱片」的 BV 号。
     * <p>
     * 这是唯一一条不走刻录机的写入路径——「换一个视频试试」在唱片机上体验更好。
     * 网络CD 没有对应能力：整张曲目表只能在刻录机里刻。
     */
    public static void swapBv(ServerPlayer player, SwapBvPacket packet) {
        BlockPos pos = packet.pos();
        if (!(player.level() instanceof ServerLevel level) || !withinRange(player, pos)) {
            return;
        }
        ItemStack disc = currentDisc(level, pos);
        if (!NetworkDiscs.isErasableDisc(disc)) {
            message(player, "message.netmusic_disc_studio.turntable_need_erasable");
            return;
        }
        ItemMusicCD.SongInfo existing = NetworkDiscs.songInfo(disc);
        if (existing != null && existing.readOnly) {
            message(player, "message.netmusic_disc_studio.disc_read_only");
            return;
        }

        String input = packet.input();
        int page = packet.page();
        FETCH_EXECUTOR.execute(() -> {
            try {
                ItemMusicCD.SongInfo info = BiliAudioResolver.resolveBiliSongInfo(input, page);
                onMain(level, () -> applyBvSwap(player, level, pos, info, input));
            } catch (Exception exception) {
                DiscStudio.LOGGER.warn("换 BV 失败: {}", input, exception);
                String reason = exception.getMessage() == null ? exception.getClass().getSimpleName()
                        : exception.getMessage();
                onMain(level, () -> message(player, "message.netmusic_disc_studio.write_failed", reason));
            }
        });
    }

    private static void applyBvSwap(ServerPlayer player, ServerLevel level, BlockPos pos,
                                    ItemMusicCD.SongInfo info, String input) {
        // 解析是异步的，期间唱片可能已经被取走或换过。
        ItemStack disc = currentDisc(level, pos);
        if (!NetworkDiscs.isErasableDisc(disc)) {
            message(player, "message.netmusic_disc_studio.disc_changed");
            return;
        }
        NetworkDiscs.writeBili(disc, info, input);
        if (!(level.getBlockEntity(pos) instanceof ModernTurntableBlockEntity turntable)) {
            return;
        }
        // setDisc 内部已经 stopPlayback() + markDirty()。
        turntable.setDisc(disc);
        turntable.startFromDisc(player);
        message(player, "message.netmusic_disc_studio.bili_written", info.songName);
    }

    // ─────────────────────────── 唱片机：切歌 ───────────────────────────

    public static void controlTrack(ServerPlayer player, DiscStudioTrackPacket packet) {
        BlockPos pos = packet.pos();
        if (!(player.level() instanceof ServerLevel level) || !withinRange(player, pos)) {
            return;
        }
        ItemStack disc = currentDisc(level, pos);
        AlbumPlaylist playlist = NetworkDiscs.playlist(disc);
        if (playlist == null || playlist.isEmpty()) {
            message(player, "message.netmusic_disc_studio.no_playlist");
            return;
        }

        AlbumPlaylist target = switch (packet.action()) {
            case SELECT -> playlist.select(packet.index());
            case NEXT -> adjacent(level, pos, playlist, 1);
            case PREV -> adjacent(level, pos, playlist, -1);
        };
        if (target.selectedIndex() == playlist.selectedIndex()) {
            // 曲目表里只有一首，或者玩家点的就是当前曲目。
            return;
        }

        NetworkDiscs.selectTrack(disc, target);
        if (!(level.getBlockEntity(pos) instanceof ModernTurntableBlockEntity turntable)) {
            return;
        }
        // setDisc 内部已经 stopPlayback() + markDirty()。
        turntable.setDisc(disc);
        turntable.startFromDisc(player);
    }

    /**
     * 手动切歌的目标。
     * <p>
     * <b>随机模式下「上一曲 / 下一曲」都随机跳</b>——既然播放顺序本身是随机的，
     * 按"顺序往前/往后挪一首"就没有意义了；这时两颗按钮都当作"换一首别的"来用，
     * 与自动放完时的行为一致（都走 {@link DiscShuffle}）。
     * 其余模式仍然是单纯的 ±1 顺/逆序。
     */
    private static AlbumPlaylist adjacent(ServerLevel level, BlockPos pos, AlbumPlaylist playlist, int delta) {
        if (loopMode(level, pos) == DiscLoopMode.SHUFFLE) {
            return DiscShuffle.nextTrack(level.getRandom(), playlist);
        }
        return playlist.step(delta);
    }

    /** 读唱片机的循环模式；拿不到（不是本模组注入过的方块实体）时按顺序播放处理。 */
    private static DiscLoopMode loopMode(ServerLevel level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof LoopModeHolder holder
                ? holder.discstudio$loopMode()
                : DiscLoopMode.SEQUENTIAL;
    }

    // ─────────────────────────── MP4：专辑切曲 ───────────────────────────

    /**
     * 把 MP4 队列里某一条「网络CD」切到第 {@code trackIndex} 首。
     * <p>
     * 一张网络CD在 MP4 队列里只占一条，"播到第几首"就写在这条物品自己的
     * {@code album_playlist} 组件里。因为这个 NBT 只有服务端说了算，
     * MP4 界面里的切歌必须走这里：先改写队列，再（可选地）把播放重启到这一条。
     * <p>
     * 曲目只按碟自带的曲目表取，客户端传什么名称/URL 都不作数。
     */
    public static void controlMp4Album(ServerPlayer player, Mp4AlbumTrackPacket packet) {
        UUID deviceId = packet.deviceId();
        if (deviceId == null || !(player.level() instanceof ServerLevel level)) {
            return;
        }
        ItemStack device = MP4Item.findByDeviceId(player, deviceId);
        if (!(device.getItem() instanceof MP4Item) || !deviceId.equals(MP4Item.readDeviceId(device))) {
            return;
        }
        List<ItemStack> queue = new ArrayList<>(MP4Item.readQueue(device));
        if (queue.isEmpty()) {
            return;
        }
        int queueIndex = Math.max(0, Math.min(queue.size() - 1, packet.queueIndex()));
        ItemStack entry = queue.get(queueIndex);
        AlbumPlaylist playlist = NetworkDiscs.playlist(entry);
        if (playlist == null || playlist.size() <= 1) {
            // 单曲条目没有"第几首"可言；真需要换歌就是换队列项，走上游那套。
            return;
        }
        int trackIndex = Math.max(0, Math.min(playlist.size() - 1, packet.trackIndex()));
        if (trackIndex != playlist.selectedIndex()) {
            NetworkDiscs.selectTrack(entry, playlist.select(trackIndex));
            Mp4QueueWriter.write(device, queue);
        }
        if (!packet.restart()) {
            return;
        }
        int volume = MP4DeviceStateStore.getOrCreate(level, deviceId, device).state().volumePerMille();
        MP4PlaybackControlPacket.restartSelected(player, device, deviceId, queueIndex, volume);
        // 与 Mp4AlbumAdvance 同一处收尾：restartSelected 内部的 stop() 会把旧曲目
        // "播到结尾"的位置写进进度存档，而恢复会话时用的正是这份进度 —— 不清零的话
        // 新曲目一开场就落在结尾前几十毫秒，几十毫秒后又"播完"，表现成连续跳歌。
        Mp4AlbumAdvance.resetTrackProgress(level, player, deviceId, device, queueIndex);
    }

    /**
     * 客户端 MP4 界面上报「此刻停在哪个视图层」（进了某张专辑的曲目表 / 停在列表层）。
     * <p>
     * 服务端唯一的用途是决定<b>自动播完时随机播放的范围</b>：在专辑里就这张碟内随机换一首，
     * 在列表层就整个队列随机换一张碟（见 {@code Mp4AlbumAdvance}）。视图层是客户端才有的概念，
     * 所以只能由它报上来。这里校验玩家确实持有这台设备，伪报最多影响自己那台 MP4。
     */
    public static void reportMp4AlbumScope(ServerPlayer player, Mp4AlbumScopePacket packet) {
        UUID deviceId = packet.deviceId();
        if (deviceId == null) {
            return;
        }
        ItemStack device = MP4Item.findByDeviceId(player, deviceId);
        if (!(device.getItem() instanceof MP4Item) || !deviceId.equals(MP4Item.readDeviceId(device))) {
            return;
        }
        Mp4AlbumScope.report(deviceId, packet.albumQueueIndex());
    }

    // ─────────────────────────── 工具 ───────────────────────────

    /** 取当前槽位唱片的副本；不要在服务端直接原地修改 {@code getDisc()} 返回的实例。 */
    private static ItemStack currentDisc(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof ModernTurntableBlockEntity turntable)) {
            return ItemStack.EMPTY;
        }
        return turntable.getDisc().copy();
    }

    private static boolean withinRange(ServerPlayer player, BlockPos pos) {
        return player.position().distanceToSqr(Vec3.atCenterOf(pos)) <= MAX_OPERATION_DISTANCE_SQR;
    }

    private static void onMain(ServerLevel level, Runnable task) {
        level.getServer().execute(task);
    }

    private static void message(ServerPlayer player, String key, Object... args) {
        player.sendSystemMessage(Component.translatable(key, args).withStyle(ChatFormatting.YELLOW));
    }
}
