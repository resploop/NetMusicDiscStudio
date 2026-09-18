package com.netmusic.discstudio.disc;

/**
 * 循环模式的读写照口。
 * <p>
 * 循环模式是唱片机的状态，但上游的 {@code ModernTurntableBlockEntity} 里根本没有这个概念，
 * 也不能被本模组继承改写——于是由 {@code ModernTurntableBlockEntityMixin} 让方块实体
 * 额外实现本接口，把 {@link DiscLoopMode} 挂上去。
 * <p>
 * 服务端的开播逻辑与客户端的界面都用 {@code instanceof LoopModeHolder} 取模式，
 * 而不互相依赖对方的 Mixin：两边看到的都是同一个方块实体的同一个字段。
 * <ul>
 *   <li>服务端：读模式决定播完是切歌还是停止；</li>
 *   <li>客户端：读模式决定按钮上写「顺序 / 单曲 / 列表 / 随机」。</li>
 * </ul>
 * 字段随 {@code saveAdditional} 一起进 NBT，因此也会随方块实体更新包同步给客户端。
 */
public interface LoopModeHolder {

    /** 循环模式在方块实体 NBT 里的键。存放位置也归接口管，免得服务端与客户端各写一份字面量。 */
    String DISC_STUDIO_LOOP_MODE = "DiscStudioLoopMode";

    /** 当前循环模式（原始值，未按"有没有曲目表"归一）。 */
    DiscLoopMode discstudio$loopMode();

    /** 写入循环模式，并同步上游的 {@code repeatOne}、标记方块实体脏数据。 */
    void discstudio$setLoopMode(DiscLoopMode mode);
}
