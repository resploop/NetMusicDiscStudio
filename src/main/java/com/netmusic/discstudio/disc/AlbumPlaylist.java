package com.netmusic.discstudio.disc;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 「网络CD」物品内保存的网易云专辑/歌单曲目表。
 * <p>
 * 整张表连同当前选中下标一起存进物品的数据组件里，因此：
 * <ul>
 *   <li>唱片机槽位里的唱片本身就是唯一数据源，客户端能从同步过来的方块实体里直接读到；</li>
 *   <li>上一曲/下一曲只需要改写下标并重新写入槽位，不需要给唱片机加新的持久化状态。</li>
 * </ul>
 *
 * @param kind          来源类型，{@link #KIND_ALBUM} 或 {@link #KIND_PLAYLIST}
 * @param sourceId      网易云专辑/歌单 ID，仅用于展示与排错
 * @param title         专辑/歌单名称，物品名直接用它
 * @param coverUrl      封面图地址，客户端据此动态生成唱片贴图；取不到时为空串
 * @param tracks        曲目列表，下标与网易云返回顺序一致
 * @param selectedIndex 当前选中曲目下标，始终落在 {@code [0, tracks.size())} 内
 */
public record AlbumPlaylist(String kind, String sourceId, String title, String coverUrl,
                            List<ItemMusicCD.SongInfo> tracks, int selectedIndex) {
    public static final String KIND_ALBUM = "album";
    public static final String KIND_PLAYLIST = "playlist";

    /**
     * 单张唱片内最多保存的曲目数。
     * <p>
     * 整张表会随方块实体同步给附近客户端，超过这个长度既没必要也会撑大同步包。
     */
    public static final int MAX_TRACKS = 200;

    public static final Codec<AlbumPlaylist> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.optionalFieldOf("kind", KIND_ALBUM).forGetter(AlbumPlaylist::kind),
            Codec.STRING.optionalFieldOf("source", "").forGetter(AlbumPlaylist::sourceId),
            Codec.STRING.optionalFieldOf("title", "").forGetter(AlbumPlaylist::title),
            Codec.STRING.optionalFieldOf("cover", "").forGetter(AlbumPlaylist::coverUrl),
            ItemMusicCD.SongInfo.CODEC.listOf().optionalFieldOf("tracks", List.of())
                    .forGetter(AlbumPlaylist::tracks),
            Codec.INT.optionalFieldOf("index", 0).forGetter(AlbumPlaylist::selectedIndex)
    ).apply(instance, AlbumPlaylist::new));

    /**
     * 曲目列表的字节流编解码。
     * <p>
     * 手写而不用 {@code ByteBufCodecs.collection}，是为了只依赖 {@link StreamCodec} 的
     * decode/encode 两个方法，避免跟随上游工具类签名变化。
     */
    public static final StreamCodec<ByteBuf, List<ItemMusicCD.SongInfo>> TRACKS_STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public List<ItemMusicCD.SongInfo> decode(ByteBuf buffer) {
                    int size = ByteBufCodecs.VAR_INT.decode(buffer);
                    List<ItemMusicCD.SongInfo> decoded = new ArrayList<>(Math.max(0, size));
                    for (int i = 0; i < size; i++) {
                        decoded.add(ItemMusicCD.SongInfo.STREAM_CODEC.decode(buffer));
                    }
                    return decoded;
                }

                @Override
                public void encode(ByteBuf buffer, List<ItemMusicCD.SongInfo> value) {
                    ByteBufCodecs.VAR_INT.encode(buffer, value.size());
                    for (ItemMusicCD.SongInfo info : value) {
                        ItemMusicCD.SongInfo.STREAM_CODEC.encode(buffer, info);
                    }
                }
            };

    public static final StreamCodec<ByteBuf, AlbumPlaylist> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, AlbumPlaylist::kind,
            ByteBufCodecs.STRING_UTF8, AlbumPlaylist::sourceId,
            ByteBufCodecs.STRING_UTF8, AlbumPlaylist::title,
            ByteBufCodecs.STRING_UTF8, AlbumPlaylist::coverUrl,
            TRACKS_STREAM_CODEC, AlbumPlaylist::tracks,
            ByteBufCodecs.VAR_INT, AlbumPlaylist::selectedIndex,
            AlbumPlaylist::new);

    public AlbumPlaylist {
        // 用"逐个滤 null"的写法而不是 List.copyOf：后者遇到 null 元素会直接抛 NPE。
        // 表可能来自接口返回、NBT 或网络包，三个来源都不该让客户端因为一条空曲目而崩。
        List<ItemMusicCD.SongInfo> cleaned = new ArrayList<>(tracks.size());
        for (ItemMusicCD.SongInfo track : tracks) {
            if (track != null) {
                cleaned.add(track);
            }
        }
        tracks = Collections.unmodifiableList(cleaned);
        selectedIndex = tracks.isEmpty() ? 0 : Math.floorMod(selectedIndex, tracks.size());
        coverUrl = coverUrl == null ? "" : coverUrl;
    }

    /** 建表并把曲目数截断到 {@link #MAX_TRACKS}。 */
    public static AlbumPlaylist of(String kind, String sourceId, String title, String coverUrl,
                                   List<ItemMusicCD.SongInfo> tracks) {
        // 顺手滤掉 null：接口返回里偶尔会出现空条目，留到 List.copyOf 或者编码时就是
        // 一个 NPE（List.copyOf 对 null 元素直接 Objects.requireNonNull）。
        List<ItemMusicCD.SongInfo> limited = new ArrayList<>(Math.min(tracks.size(), MAX_TRACKS));
        for (ItemMusicCD.SongInfo track : tracks) {
            if (track == null) {
                continue;
            }
            limited.add(track);
            if (limited.size() >= MAX_TRACKS) {
                break;
            }
        }
        return new AlbumPlaylist(kind, sourceId, title, coverUrl, limited, 0);
    }

    public boolean isEmpty() {
        return tracks.isEmpty();
    }

    public int size() {
        return tracks.size();
    }

    /** 当前选中曲目；空表返回 {@code null}。 */
    public ItemMusicCD.SongInfo currentTrack() {
        return isEmpty() ? null : tracks.get(selectedIndex);
    }

    /** 显示用的专辑名，标题为空时退回 ID。 */
    public String displayTitle() {
        return title == null || title.isBlank() ? sourceId : title;
    }

    /** 相对移动下标并首尾循环；空表或零位移返回自身。 */
    public AlbumPlaylist step(int delta) {
        if (isEmpty() || delta == 0) {
            return this;
        }
        return new AlbumPlaylist(kind, sourceId, title, coverUrl, tracks,
                Math.floorMod(selectedIndex + delta, tracks.size()));
    }

    /** 直接定位到某条曲目；越界返回自身。 */
    public AlbumPlaylist select(int index) {
        if (isEmpty() || index < 0 || index >= tracks.size() || index == selectedIndex) {
            return this;
        }
        return new AlbumPlaylist(kind, sourceId, title, coverUrl, tracks, index);
    }

    /** 曲目数是否顶到了上限（提示玩家后面还有歌没写进来）。 */
    public boolean truncated() {
        return tracks.size() >= MAX_TRACKS;
    }
}
