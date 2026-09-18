package com.netmusic.discstudio.api;

import com.github.tartaricacid.netmusic.NetMusic;
import com.github.tartaricacid.netmusic.api.ExtraMusicList;
import com.github.tartaricacid.netmusic.api.pojo.NetEaseMusicList;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.netmusic.discstudio.DiscStudio;
import com.netmusic.discstudio.disc.AlbumPlaylist;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 网易云专辑 / 歌单拉取。
 * <p>
 * 复用 NetMusic 已经封装好的 {@code WebApi}（含它维护的 Cookie、加密参数与代理配置），
 * 本模组只做三件它没做的事：
 * <ol>
 *   <li>专辑接口 {@code WebApi#album(long)} 返回的 {@code songs} 数组映射为 {@code ExtraMusicList}；</li>
 *   <li>歌单接口返回的 {@code tracks} 往往只有前若干首，用 {@code trackIds} 分批补全并保持歌单顺序；</li>
 *   <li>从原始 JSON 里额外取出专辑名与<b>封面地址</b>（上游的 POJO 里没有这两个字段）。</li>
 * </ol>
 * 全部方法都会发起阻塞 HTTP 请求，必须在工作线程调用。
 */
public final class NetEaseAlbumApi {
    /** {@code songs} 接口一次能接受的 ID 数量，保守取 50，避免 URL 过长。 */
    private static final int BATCH_SIZE = 50;

    private static final Gson GSON = new Gson();

    /**
     * 专辑封面地址的候选字段，按优先级排列。
     * <p>
     * 网易云不同接口/不同时期返回的字段名并不统一，只认 {@code picUrl} 的话一旦遇到
     * {@code blurPicUrl} 之类的变体就会静默退化成默认贴图，所以这里多试几个。
     * 数组元素是 {@code {对象名, 字段名}}，对象名为空串表示根对象。
     */
    private static final String[][] ALBUM_COVER_FIELDS = {
            {"album", "picUrl"},
            {"album", "blurPicUrl"},
            {"", "picUrl"},
    };

    private static final String[][] PLAYLIST_COVER_FIELDS = {
            {"playlist", "coverImgUrl"},
            {"result", "coverImgUrl"},
            {"", "coverImgUrl"},
    };

    private NetEaseAlbumApi() {
    }

    public static AlbumPlaylist fetch(NetEaseReference reference) throws Exception {
        if (AlbumPlaylist.KIND_PLAYLIST.equals(reference.kind())) {
            return fetchPlaylist(reference.id());
        }
        return fetchAlbum(reference.id());
    }

    /** 拉取整张专辑。 */
    public static AlbumPlaylist fetchAlbum(long albumId) throws Exception {
        String json = NetMusic.NET_EASE_WEB_API.album(albumId);
        ExtraMusicList response = GSON.fromJson(json, ExtraMusicList.class);
        List<NetEaseMusicList.Track> tracks = response == null ? null : response.getTracks();
        if (tracks == null || tracks.isEmpty()) {
            throw new IOException("专辑没有返回任何曲目");
        }
        String title = readNestedString(json, "album", "name");
        String cover = findCover(json, ALBUM_COVER_FIELDS);
        DiscStudio.LOGGER.info("封面地址（专辑 {}）: {}", albumId, cover.isBlank() ? "<未取到>" : cover);
        return AlbumPlaylist.of(AlbumPlaylist.KIND_ALBUM, Long.toString(albumId), title, cover,
                toSongInfos(tracks));
    }

    /** 拉取歌单，并用 {@code trackIds} 补全被详情接口截断的曲目。 */
    public static AlbumPlaylist fetchPlaylist(long playlistId) throws Exception {
        String json = NetMusic.NET_EASE_WEB_API.list(playlistId);
        NetEaseMusicList response = GSON.fromJson(json, NetEaseMusicList.class);
        NetEaseMusicList.PlayList playlist = response == null ? null : response.getPlayList();
        if (playlist == null) {
            throw new IOException("歌单不存在，或该歌单没有公开曲目");
        }

        String title = playlist.getName() == null ? "" : playlist.getName();
        String cover = findCover(json, PLAYLIST_COVER_FIELDS);
        List<NetEaseMusicList.Track> tracks = new ArrayList<>();
        if (playlist.getTracks() != null) {
            tracks.addAll(playlist.getTracks());
        }

        List<Long> orderedIds = orderedTrackIds(playlist);
        if (!orderedIds.isEmpty()) {
            // 详情接口返回的 tracks 是有序的但常常被截断，因此以 trackIds 为准重新拉一遍。
            List<NetEaseMusicList.Track> complete = fetchTracksByIds(orderedIds);
            if (!complete.isEmpty()) {
                tracks = complete;
            }
        }
        if (tracks.isEmpty()) {
            throw new IOException("歌单没有返回任何曲目");
        }
        DiscStudio.LOGGER.info("封面地址（歌单 {}）: {}", playlistId, cover.isBlank() ? "<未取到>" : cover);
        return AlbumPlaylist.of(AlbumPlaylist.KIND_PLAYLIST, Long.toString(playlistId), title, cover,
                toSongInfos(tracks));
    }

    private static List<Long> orderedTrackIds(NetEaseMusicList.PlayList playlist) {
        List<NetEaseMusicList.PlayList.TrackId> trackIds = playlist.getTrackIds();
        if (trackIds == null || trackIds.isEmpty()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>(Math.min(trackIds.size(), AlbumPlaylist.MAX_TRACKS));
        for (NetEaseMusicList.PlayList.TrackId trackId : trackIds) {
            ids.add(trackId.getId());
            if (ids.size() >= AlbumPlaylist.MAX_TRACKS) {
                break;
            }
        }
        return ids;
    }

    private static List<NetEaseMusicList.Track> fetchTracksByIds(List<Long> ids) throws Exception {
        List<NetEaseMusicList.Track> result = new ArrayList<>(ids.size());
        for (int offset = 0; offset < ids.size(); offset += BATCH_SIZE) {
            List<Long> slice = ids.subList(offset, Math.min(ids.size(), offset + BATCH_SIZE));
            long[] batch = new long[slice.size()];
            for (int i = 0; i < batch.length; i++) {
                batch[i] = slice.get(i);
            }
            String json = NetMusic.NET_EASE_WEB_API.songs(batch);
            ExtraMusicList extra = GSON.fromJson(json, ExtraMusicList.class);
            if (extra != null && extra.getTracks() != null) {
                result.addAll(extra.getTracks());
            }
        }
        return result;
    }

    private static List<ItemMusicCD.SongInfo> toSongInfos(List<NetEaseMusicList.Track> tracks) {
        List<ItemMusicCD.SongInfo> songs = new ArrayList<>(tracks.size());
        for (NetEaseMusicList.Track track : tracks) {
            songs.add(new ItemMusicCD.SongInfo(track));
        }
        return songs;
    }

    /**
     * 从原始 JSON 里按候选字段顺序找封面地址，全都取不到时退回第一首曲目的专辑封面。
     *
     * @return 一定返回字符串；取不到时是空串（此时客户端会退化成默认唱片贴图）
     */
    private static String findCover(String json, String[][] candidates) {
        JsonObject root = parse(json);
        if (root == null) {
            return "";
        }
        for (String[] candidate : candidates) {
            String value = readString(root, candidate[0], candidate[1]);
            if (!value.isBlank()) {
                return normalizeCover(value);
            }
        }
        return firstSongCover(root);
    }

    /**
     * 取第一首曲目所属专辑的封面。
     * <p>
     * 专辑/歌单响应里的每首歌都带 {@code al.picUrl}，这是最可靠的兜底：即使外层的
     * 封面字段改名了，只要曲目列表正常就还能拿到图。
     */
    private static String firstSongCover(JsonObject root) {
        JsonElement songs = root.get("songs");
        if (songs == null || !songs.isJsonArray()) {
            return "";
        }
        JsonArray array = songs.getAsJsonArray();
        if (array.isEmpty()) {
            return "";
        }
        JsonElement first = array.get(0);
        if (first == null || !first.isJsonObject()) {
            return "";
        }
        JsonObject song = first.getAsJsonObject();
        for (String key : new String[]{"al", "album"}) {
            String value = readString(song, key, "picUrl");
            if (!value.isBlank()) {
                return normalizeCover(value);
            }
        }
        return "";
    }

    /** 网易云有时返回 {@code http://} 的图片地址，统一升到 https，避免被降级重定向挡在半路。 */
    private static String normalizeCover(String url) {
        String trimmed = url.trim();
        return trimmed.startsWith("http://")
                ? "https://" + trimmed.substring("http://".length())
                : trimmed;
    }

    /** 取 {@code {"<objectKey>":{"<fieldKey>":...}}}；{@code objectKey} 为空串表示直接读根对象。 */
    private static String readString(JsonObject root, String objectKey, String fieldKey) {
        JsonElement holder;
        if (objectKey.isEmpty()) {
            holder = root;
        } else {
            JsonElement nested = root.get(objectKey);
            if (nested == null || !nested.isJsonObject()) {
                return "";
            }
            holder = nested;
        }
        JsonElement value = holder.getAsJsonObject().get(fieldKey);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    /** 从原始 JSON 里取 {@code {"<objectKey>":{"<fieldKey>":"..."}}} 形式的字符串，失败返回空串。 */
    private static String readNestedString(String json, String objectKey, String fieldKey) {
        JsonObject root = parse(json);
        return root == null ? "" : readString(root, objectKey, fieldKey);
    }

    private static JsonObject parse(String json) {
        try {
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException exception) {
            DiscStudio.LOGGER.warn("解析网易云响应失败", exception);
            return null;
        }
    }
}
