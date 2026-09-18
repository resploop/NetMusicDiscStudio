package com.netmusic.discstudio.disc;

import net.minecraft.util.RandomSource;

/**
 * 随机播放的"挑下一首"算法。
 * <p>
 * 两条路径都要用它，所以抽出来共用：
 * <ul>
 *   <li><b>自动</b>——{@code ModernTurntableBlockEntityMixin} 在一首歌放完时切曲；</li>
 *   <li><b>手动</b>——玩家在唱片机界面点「下一曲 / 上一曲」，
 *       {@code DiscStudioService#controlTrack} 处理。</li>
 * </ul>
 * 两边用同一个函数，才能保证"点下一曲跳到的歌"和"放完自动跳到的歌"是同一套随机行为。
 */
public final class DiscShuffle {
    private DiscShuffle() {
    }

    /**
     * 从 {@code [0, size)} 里随机挑一个<b>不等于</b> {@code current} 的下标。
     * <p>
     * 只把取值区间 {@code [0, size-2]} 中落在 {@code current} 及之后的部分整体后移一位，
     * 就得到了 {@code [0, size) \ {current}} 上的均匀分布——比"抽到重复就重抽"的写法少一次
     * 循环，也不会有 {@code size == 2} 时反复抽到同一个值的退化。
     *
     * @param random  用哪台随机源（服务端一律传 {@code level.getRandom()}，别自己 new）
     * @param size    曲目数；小于 2 时无解，原样返回 {@code current}
     * @param current 当前曲目下标
     */
    public static int indexOtherThan(RandomSource random, int size, int current) {
        if (size <= 1) {
            return current;
        }
        int offset = random.nextInt(size - 1);
        return offset >= current ? offset + 1 : offset;
    }

    /**
     * 随机模式下的切歌目标：跳到表里另一首。
     *
     * @return 新的曲目表；曲目数不足 2 首时原样返回 {@code playlist}
     */
    public static AlbumPlaylist nextTrack(RandomSource random, AlbumPlaylist playlist) {
        if (playlist == null || playlist.size() <= 1) {
            return playlist;
        }
        return playlist.select(indexOtherThan(random, playlist.size(), playlist.selectedIndex()));
    }
}
