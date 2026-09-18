package com.netmusic.discstudio.mixin.client;

import com.github.tartaricacid.netmusic.client.gui.CDBurnerMenuScreen;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.netmusic.discstudio.api.NetEaseReference;
import com.netmusic.discstudio.disc.NetworkDiscs;
import com.netmusic.discstudio.network.BurnAlbumPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 唱片刻录机界面里对「网络CD」的分支处理。
 * <p>
 * 上游刻录按钮只认「dj/ID」和「纯数字单曲 ID」两种输入，专辑链接会直接报
 * {@code music_id_error}。这里在 HEAD 处接管：输入槽是「网络CD」时，
 * 校验输入是不是专辑/歌单链接，是就把原文发到服务端异步解析。
 * <p>
 * 「可擦写网络唱片」不走这里——它的 BV 写入由 NetMusicCanPlayBili 自己的
 * {@code CDBurnerMenuScreenMixin} 处理（那段逻辑不检查槽里是什么碟），
 * 我这边只需要放开刻录机的槽位白名单。
 * <p>
 * 对「网络CD」一律 cancel：即使玩家填的是单曲 ID，也只给提示，不让上游
 * 把单曲写进网络CD；服务端的 {@code CDBurnerMenuMixin} 还会再兜一层。
 */
@Mixin(CDBurnerMenuScreen.class)
public abstract class CDBurnerMenuScreenMixin {

    @Shadow
    private EditBox textField;

    @Shadow
    private Component tips;

    /**
     * 放宽输入框长度上限。
     * <p>
     * 上游把输入框卡在 {@code setMaxLength(19)}，那是为「纯数字单曲 ID / dj 号」设计的：
     * 连一条 B 站视频链接（{@code https://www.bilibili.com/video/BV...}，40 字符以上）
     * 都粘不进去，网易云专辑分享链接更不可能。放宽到 512 之后链接才能被完整粘贴，
     * 本模组与 NetMusicCanPlayBili 的链接式输入才有意义。
     * <p>
     * 放宽不会破坏上游行为：单曲 ID 与 dj 号的解析规则不变，只是超长输入不再被静默截断。
     */
    @Inject(method = "init", at = @At("TAIL"))
    private void discstudio$widenInputLimit(CallbackInfo ci) {
        if (textField != null) {
            textField.setMaxLength(512);
        }
    }

    @Inject(method = "handleCraftButton", at = @At("HEAD"), cancellable = true)
    private void discstudio$burnAlbumCd(CallbackInfo ci) {
        CDBurnerMenuScreen screen = (CDBurnerMenuScreen) (Object) this;
        ItemStack disc = screen.getMenu().getInput().getResource(0).toStack();
        if (!NetworkDiscs.isAlbumCd(disc)) {
            return;
        }
        ci.cancel();

        String text = textField == null ? "" : textField.getValue().trim();
        if (NetEaseReference.parseStrict(text).isEmpty()) {
            tips = Component.translatable("gui.netmusic_disc_studio.burn.need_album_link");
            return;
        }

        ItemMusicCD.SongInfo existing = ItemMusicCD.getSongInfo(disc);
        if (existing != null && existing.readOnly) {
            tips = Component.translatable("gui.netmusic.cd_burner.cd_read_only");
            return;
        }

        tips = Component.translatable("gui.netmusic_disc_studio.burn.resolving");
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() != null) {
            minecraft.getConnection().send(new BurnAlbumPacket(text));
        }
    }
}
