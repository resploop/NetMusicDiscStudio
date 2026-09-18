package com.netmusic.discstudio.api;

import com.netmusic.discstudio.disc.AlbumPlaylist;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从玩家输入里认出网易云的专辑 / 歌单。
 *
 * @param kind {@link AlbumPlaylist#KIND_ALBUM} 或 {@link AlbumPlaylist#KIND_PLAYLIST}
 * @param id   专辑或歌单 ID
 */
public record NetEaseReference(String kind, long id) {
    /** 分享链接里常见的形式：{@code /album?id=123}、{@code /#/playlist?id=123}、{@code /discover/toplist?id=123}。 */
    private static final Pattern PATH_WITH_ID = Pattern.compile(
            "(album|playlist|toplist|djradio|radio)[^\\d]{0,24}?(\\d{4,})",
            Pattern.CASE_INSENSITIVE);

    /** 仅 {@code id=123} 的情况。 */
    private static final Pattern BARE_ID = Pattern.compile("(?:^|[?&#])id=(\\d{4,})", Pattern.CASE_INSENSITIVE);

    private static final Pattern DIGITS_ONLY = Pattern.compile("^\\d{4,}$");

    /**
     * 刻录机场景专用：只接受明确写了 album / playlist / toplist 的链接。
     * <p>
     * {@link #parse(String)} 会把纯数字当成专辑 ID，但网易云的「单曲 ID」也是纯数字，
     * 两者在刻录机的同一个输入框里无法区分——而 NetMusic 上游本身就会把纯数字
     * 当成单曲刻录。用严格模式把歧义挡在外面，提示玩家粘贴分享链接即可。
     */
    public static Optional<NetEaseReference> parseStrict(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String lower = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (!lower.contains("album") && !lower.contains("playlist") && !lower.contains("toplist")) {
            return Optional.empty();
        }
        return parse(raw);
    }

    public static Optional<NetEaseReference> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return Optional.empty();
        }

        if (DIGITS_ONLY.matcher(text).matches()) {
            // 纯数字无法区分专辑和歌单，按需求文档的默认约定当作专辑处理。
            return Optional.of(new NetEaseReference(AlbumPlaylist.KIND_ALBUM, Long.parseLong(text)));
        }

        Matcher pathMatcher = PATH_WITH_ID.matcher(text);
        if (pathMatcher.find()) {
            String keyword = pathMatcher.group(1).toLowerCase(java.util.Locale.ROOT);
            return Optional.of(new NetEaseReference(kindOf(keyword), Long.parseLong(pathMatcher.group(2))));
        }

        Matcher bareId = BARE_ID.matcher(text);
        if (bareId.find()) {
            String keyword = text.toLowerCase(java.util.Locale.ROOT);
            String kind = keyword.contains("playlist") || keyword.contains("toplist")
                    ? AlbumPlaylist.KIND_PLAYLIST
                    : AlbumPlaylist.KIND_ALBUM;
            return Optional.of(new NetEaseReference(kind, Long.parseLong(bareId.group(1))));
        }

        return Optional.empty();
    }

    private static String kindOf(String keyword) {
        return switch (keyword) {
            case "playlist", "toplist", "djradio", "radio" -> AlbumPlaylist.KIND_PLAYLIST;
            default -> AlbumPlaylist.KIND_ALBUM;
        };
    }
}
