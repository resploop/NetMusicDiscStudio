package com.netmusic.discstudio.mixin;

import com.github.tartaricacid.netmusic.inventory.CDBurnerMenu;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.netmusic.discstudio.disc.AlbumPlaylist;
import com.netmusic.discstudio.disc.NetworkDiscs;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 守住「网络CD 的内容只能来自专辑/歌单解析」这条不变量。
 * <p>
 * 上游的刻录流程是「客户端解析出一条 {@code SongInfo} → 服务端调
 * {@link CDBurnerMenu#setSongInfo} 把输入槽的碟搬到输出槽」。
 * 如果不加约束，玩家对着「网络CD」填一个单曲 ID 或 BV 号，
 * 就会刻出一张只有单曲、没有曲目表的网络CD——播放能播，但选曲功能是空的。
 * <p>
 * 这里在服务端统一拦截：输入槽是网络CD 时，只有当它自身携带的曲目表里
 * 当前这一首正好等于要写入的那一首（也就是本模组的专辑刻录流程）才放行。
 * <p>
 * 拒绝时直接取消，碟会留在输入槽，不会凭空消失。
 */
@Mixin(CDBurnerMenu.class)
public abstract class CDBurnerMenuMixin {
    @Inject(method = "setSongInfo", at = @At("HEAD"), cancellable = true)
    private void discstudio$keepAlbumCdConsistent(ItemMusicCD.SongInfo songInfo, CallbackInfo ci) {
        CDBurnerMenu menu = (CDBurnerMenu) (Object) this;
        ItemStack input = menu.getInput().getResource(0).toStack();
        if (!NetworkDiscs.isAlbumCd(input)) {
            return;
        }
        AlbumPlaylist playlist = NetworkDiscs.playlist(input);
        if (playlist == null || !NetworkDiscs.sameTrack(playlist.currentTrack(), songInfo)) {
            ci.cancel();
        }
    }
}
