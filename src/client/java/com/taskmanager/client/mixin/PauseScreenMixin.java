package com.taskmanager.client.mixin;

import com.taskmanager.client.gui.TaskManagerScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在暂停菜单（原版 UI）注册「任务管理器」按钮，作为进入模组 GUI 的渠道。
 */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin {
	@Shadow
	protected int width;
	@Shadow
	protected int height;

	@Shadow
	protected abstract <T extends GuiEventListener & Renderable & NarratableEntry> T addRenderableWidget(T widget);

	@Inject(method = "init", at = @At("TAIL"))
	private void addTaskManagerButton(CallbackInfo ci) {
		this.addRenderableWidget(Button.builder(Component.literal("任务管理器"), button ->
			Minecraft.getInstance().gui.setScreen(new TaskManagerScreen())
		).width(204).pos(this.width / 2 - 102, this.height / 4 + 152).build());
	}
}
