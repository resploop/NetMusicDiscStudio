package com.netmusic.discstudio.bili;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端保存的「客户端 MP4 界面停在哪个视图层」。
 *
 * <p>只为一件事存在：上游的 {@code shuffle} 只是个开关，<b>自动播完时的推进</b>
 * （{@code MP4PlaybackSyncManager} 每刻检查时长后推队列）完全不看它。
 * 本模组要补上随机播放，就必须知道玩家此刻是在某张专辑的曲目表里、
 * 还是停在队列列表层 —— 前者在专辑内随机换一首，后者在整个队列里随机换一张碟。
 *
 * <p>这个值由客户端 {@code Mp4AlbumScopePacket} 上报（进专辑 / 返回 / 队列刷新时更新）。
 * 视图层是客户端的东西，服务端无法自己推导，所以这里只是一份镜像，读不到时
 * 一律按"列表层"处理（即整队列随机）。
 */
public final class Mp4AlbumScope {
    private Mp4AlbumScope() {
    }

    /** 设备 → 正打开着的专辑在队列里的下标。不在表里 = 停在列表层。 */
    private static final Map<UUID, Integer> SCOPES = new ConcurrentHashMap<>();

    /**
     * 客户端上报视图层。
     *
     * @param albumQueueIndex 正打开的专辑在队列里的下标；{@code -1} 表示列表层
     */
    public static void report(UUID deviceId, int albumQueueIndex) {
        if (deviceId == null) {
            return;
        }
        if (albumQueueIndex < 0) {
            SCOPES.remove(deviceId);
        } else {
            SCOPES.put(deviceId, albumQueueIndex);
        }
    }

    /** 该设备此刻是不是正停在队列第 {@code queueIndex} 条这张专辑的曲目表里。 */
    public static boolean browsing(UUID deviceId, int queueIndex) {
        if (deviceId == null) {
            return false;
        }
        Integer scope = SCOPES.get(deviceId);
        return scope != null && scope == queueIndex;
    }

    /** 忘掉某台设备（队列被清空、设备换手时用）。 */
    public static void forget(UUID deviceId) {
        if (deviceId != null) {
            SCOPES.remove(deviceId);
        }
    }
}
