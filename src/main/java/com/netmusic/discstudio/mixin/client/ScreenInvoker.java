package com.netmusic.discstudio.mixin.client;

import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@code Screen#addRenderableWidget} 是 protected 的，Mixin 类不是 Screen 的子类，
 * 因此用 Invoker 暴露出来给按钮注入代码使用。
 */
@Mixin(Screen.class)
public interface ScreenInvoker {
    @Invoker("addRenderableWidget")
    <T extends GuiEventListener & Renderable & NarratableEntry> T discstudio$addRenderableWidget(T widget);
}
