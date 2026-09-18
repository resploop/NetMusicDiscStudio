package com.netmusic.discstudio.mixin.client;

import com.github.tartaricacid.netmusic.client.gui.ComputerMenuScreen;
import com.netmusic.discstudio.client.screen.NetEaseLoginScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 NetMusic 的「电脑」界面里加一颗「登录网易云」按钮。
 * <p>
 * 选这里放按钮的理由：电脑是玩家与"网络歌曲"打交道的地方（输入 URL、歌名、时长，刻出唱片），
 * 账号也属于同一件事。唱片机、刻录机都只是设备，不该出现账号入口。
 * <p>
 * <b>布局（按面板局部坐标，面板 176×216）：</b>
 * <ul>
 *   <li>URL 输入框 {@code 10..130, 18..34}（{@code +10,+18} 120×16，无边框，底纹由贴图画）；</li>
 *   <li>歌名 {@code 10..130, 39..55}；时长 {@code 10..50, 61..77}；</li>
 *   <li>只读复选框 {@code 58..138, 55..75}；刻录按钮 {@code 7..142, 78..96}；</li>
 *   <li>输入槽位 {@code 147..165, 14..32}、输出槽位 {@code 147..165, 79..97}；</li>
 *   <li>玩家背包三行 {@code 8..170, 134..188}、快捷栏 {@code 8..170, 192..210}。</li>
 * </ul>
 * <p>
 * <b>为什么放在 {@code +135,+98}：</b>之前放在 {@code +133,+18}，而 {@code y=18} 那一行
 * 已经被 URL 输入框（到 {@code x=130} 收边）和输入槽位（从 {@code x=147} 起）占满，
 * 40 宽的按钮正好压在两者之间的窄缝上，视觉上就是"登录键偏移、挡住输入框"。
 * 现在挪到 {@code y=98} 这一行：NetMusicCanPlayBili 的「B站登录」按钮在
 * {@code urlTextField.x+0}（即 {@code 10..70}）、「杜比」在 {@code +64..+134}，
 * 右侧 {@code 135..175} 恰好空着，四个按钮排成整齐一行，且离面板右缘还留 1 像素。
 * <p>
 * <b>唯一的视觉副作用：</b>刻录报错时 NetMusic 会在 {@code +8,+100} 起画一段红字提示，
 * 长错误信息的尾巴会跑到按钮底下。属于纯观感问题，不影响点击（按钮在 widget 层，先于文本命中）。
 * <p>
 * <b>为什么不用 {@code @Shadow} 拿 leftPos/topPos：</b>这两个字段声明在父类
 * {@code AbstractContainerScreen} 上而不是目标类 {@code ComputerMenuScreen} 自己。
 * Mixin 解析 {@code @Shadow} 不会沿父类找，会直接抛
 * {@code InvalidMixinException: @Shadow field leftPos was not located in the target class}
 * 并让整个游戏崩溃（实测于 26.1.2）。改用 NeoForge 给
 * {@code AbstractContainerScreen} 补的公开读取器 {@code getLeftPos()/getTopPos()}，
 * 彻底不碰 Mixin 的成员解析，同样的位置、零风险。
 * <p>
 * <b>副作用：</b>点开会用 {@code setScreen} 换到登录界面，原容器界面随之关闭，
 * 上游 {@code ComputerMenu#removed} 会把输入/输出槽里的东西退回玩家背包——
 * 不会丢东西，但手上的唱片会回到包里，这一点在 {@code removed} 里已有行为，本模组不额外处理。
 */
@Mixin(ComputerMenuScreen.class)
public abstract class ComputerMenuScreenMixin {

    @Inject(method = "init", at = @At("TAIL"))
    private void discstudio$addNetEaseLoginButton(CallbackInfo ci) {
        // 面板原点取自 NeoForge 在 AbstractContainerScreen 上加的公开读取器，
        // 避免对"父类字段"使用 @Shadow（Mixin 不沿父类解析，会炸）。
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        // y=98 是「B站登录 / 杜比」那颗按钮所在的整行；右侧 135..175 是这一行唯一空着的地方，
        // 不会再和 URL 输入框（10..130）或输入槽位（147..165）打架。
        int x = self.getLeftPos() + 135;
        int y = self.getTopPos() + 98;

        ScreenInvoker invoker = (ScreenInvoker) (Object) this;
        invoker.discstudio$addRenderableWidget(Button.builder(
                        Component.translatable("gui.netmusic_disc_studio.netease.button"),
                        button -> Minecraft.getInstance().setScreen(new NetEaseLoginScreen()))
                .pos(x, y)
                .size(40, 18)
                .build());
    }
}
