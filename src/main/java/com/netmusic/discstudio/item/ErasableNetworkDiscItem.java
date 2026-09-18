package com.netmusic.discstudio.item;

import net.minecraft.resources.Identifier;

/**
 * 「可擦写网络唱片」。
 * <p>
 * 一张可以反复改写的空盘：放进现代化唱片机后直接输入 BV/av 号即可播放或切换视频，
 * 不需要像原版那样先用刻录机刻成 {@code netmusic:music_cd}。
 * <p>
 * 写入的内容以 NetMusic 自己的 {@code netmusic:song_info} 组件保存，
 * 播放时由 NetMusicCanPlayBili 的 {@code BiliAudioResolver} 解析成真实音频直链。
 */
public class ErasableNetworkDiscItem extends NetworkDiscItem {
    public ErasableNetworkDiscItem(Identifier id) {
        super(id);
    }

    @Override
    protected String usageKey() {
        return "tooltip.netmusic_disc_studio.erasable.usage";
    }
}
