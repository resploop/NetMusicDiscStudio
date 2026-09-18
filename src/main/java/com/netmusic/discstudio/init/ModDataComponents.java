package com.netmusic.discstudio.init;

import com.mojang.serialization.Codec;
import com.netmusic.discstudio.DiscStudio;
import com.netmusic.discstudio.disc.AlbumPlaylist;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 本模组自己的数据组件。
 * <p>
 * 曲目信息本身复用 NetMusic 的 {@code netmusic:song_info}（组件是物品无关的，
 * 只要在 mixin 里让 {@code ItemMusicCD.getSongInfo} 认识我们的物品即可），
 * 这里只补充"写盘时用户输入的原文"和"整张曲目表"。
 * <p>
 * 两个组件都必须有 persistent codec：唱片机的方块实体通过
 * {@code saveWithoutMetadata} 把槽位里的 ItemStack 同步给客户端，
 * 缺少持久化编解码器的组件会让这一步直接抛异常。
 */
public final class ModDataComponents {
    public static final DeferredRegister.DataComponents DATA_COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, DiscStudio.MOD_ID);

    /** 用户写入时输入的原文（BV 链接 / 专辑链接），用于界面回显与"重新写入"。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> DISC_SOURCE =
            DATA_COMPONENTS.register("disc_source", () -> DataComponentType.<String>builder()
                    .persistent(Codec.STRING)
                    .networkSynchronized(ByteBufCodecs.STRING_UTF8)
                    .build());

    /** 「网络CD」内的曲目表与当前下标。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<AlbumPlaylist>> ALBUM_PLAYLIST =
            DATA_COMPONENTS.register("album_playlist", () -> DataComponentType.<AlbumPlaylist>builder()
                    .persistent(AlbumPlaylist.CODEC)
                    .networkSynchronized(AlbumPlaylist.STREAM_CODEC)
                    .build());

    private ModDataComponents() {
    }
}
