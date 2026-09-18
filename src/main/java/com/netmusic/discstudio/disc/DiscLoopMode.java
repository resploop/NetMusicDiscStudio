package com.netmusic.discstudio.disc;

/**
 * 现代化唱片机的播放循环模式。
 * <p>
 * 上游只认识一个布尔量 {@code repeatOne}——要么"播完停下"，要么"单曲循环"。
 * 本模组在它之上补出「列表循环」与「随机播放」，让「网络CD」这一整张曲目表可以顺着放、
 * 也可以打乱着放。
 * <p>
 * 四种模式在"一首歌放完"这一刻的行为：
 * <table border="1">
 *   <tr><th>模式</th><th>还有下一首</th><th>已经是最后一首</th></tr>
 *   <tr><td>{@link #SEQUENTIAL}</td><td>自动切下一首</td><td>停止播放</td></tr>
 *   <tr><td>{@link #REPEAT_ONE}</td><td colspan="2">重播当前这一首（上游原有行为）</td></tr>
 *   <tr><td>{@link #REPEAT_ALL}</td><td>自动切下一首</td><td>回到第一首继续放</td></tr>
 *   <tr><td>{@link #SHUFFLE}</td><td colspan="2">随机跳到表里另一首，可能重复但不会连放同一首</td></tr>
 * </table>
 * <p>
 * 「播完提取」的判定跟着停止走：只有整张曲目表放到最后一首结束、真的停下来那一次，
 * 才把唱片机标记为"播放完成"，漏斗/管道这时才允许把盘抽走。
 * {@code REPEAT_ONE}、{@code REPEAT_ALL} 与 {@code SHUFFLE} 永远不会停止，
 * 因此也永远不会被自动提取。
 */
public enum DiscLoopMode {
    /** 顺序播放：整张表放一遍就停。 */
    SEQUENTIAL("sequential"),
    /** 单曲循环：只重复当前这一首。 */
    REPEAT_ONE("repeat_one"),
    /** 列表循环：整张表放完回到第一首。 */
    REPEAT_ALL("repeat_all"),
    /** 随机播放：每次放完随机跳到另一首。 */
    SHUFFLE("shuffle");

    private final String serializedName;

    DiscLoopMode(String serializedName) {
        this.serializedName = serializedName;
    }

    public String serializedName() {
        return serializedName;
    }

    /**
     * 轮转到的下一个模式：顺序 → 单曲 → 列表 → 随机 → 顺序。
     * <p>
     * 没有曲目表时跳过「列表循环」与「随机播放」——那种唱片的"列表"只有一首，
     * 两者都与「单曲循环」完全等价，多摆状态只会让玩家困惑。
     */
    public DiscLoopMode next(boolean hasPlaylist) {
        return switch (this) {
            case SEQUENTIAL -> REPEAT_ONE;
            case REPEAT_ONE -> hasPlaylist ? REPEAT_ALL : SEQUENTIAL;
            case REPEAT_ALL -> hasPlaylist ? SHUFFLE : SEQUENTIAL;
            case SHUFFLE -> SEQUENTIAL;
        };
    }

    /**
     * 实际生效的模式。
     * <p>
     * 模式是唱片机的状态，换盘不会重置。所以「列表循环」「随机播放」有可能是上一张专辑
     * 留下的，而当前槽位里只是一张单曲唱片——这时把它当成「顺序播放」，界面显示的、
     * 真正播放的与上游语义三者才一致。
     */
    public DiscLoopMode effective(boolean hasPlaylist) {
        return !hasPlaylist && needsPlaylist() ? SEQUENTIAL : this;
    }

    /** 这个模式是否依赖曲目表（表不存在或只有一首时无意义）。 */
    public boolean needsPlaylist() {
        return this == REPEAT_ALL || this == SHUFFLE;
    }

    /** 上游 {@code repeatOne} 这个布尔量该不该置位（它只代表"重播当前曲"）。 */
    public boolean repeatsCurrentTrack() {
        return this == REPEAT_ONE;
    }

    public static DiscLoopMode byName(String name) {
        for (DiscLoopMode mode : values()) {
            if (mode.serializedName.equals(name)) {
                return mode;
            }
        }
        return SEQUENTIAL;
    }
}
