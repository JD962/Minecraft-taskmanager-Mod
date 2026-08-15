package com.taskmanager.client.mixin;

import com.taskmanager.client.gui.TaskManagerScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在暂停菜单（原版 UI）注册「任务管理器」按钮，作为进入模组 GUI 的渠道。
 *
 * <p>两个关键点（均为 MC 26.2 + Loom 1.17 no-remap 环境下的已知坑）：</p>
 * <ol>
 *   <li>{@code extends Screen}：Mixin 要求 Mixin 类的父类必须是 target 类（PauseScreen）的<b>祖先</b>，
 *       不能是 target 本身；且 {@code @Shadow} 只在 target 的直接成员表中查找、不沿父类解析。
 *       故让 Mixin 继承 {@code Screen}（PauseScreen 的直接父类），直接访问继承的
 *       {@code addRenderableWidget}/{@code width}/{@code height}，无需 @Shadow。</li>
 *   <li>{@code remap = false}：MC 26.1+ 起 Mojang 不再混淆代码（unobfuscated），官方名即运行时名，
 *       Loom 1.17 不生成 refMap，必须显式关闭映射。</li>
 * </ol>
 */
@Mixin(value = PauseScreen.class, remap = false)
public abstract class PauseScreenMixin extends Screen {

	protected PauseScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "init()V", at = @At("TAIL"), remap = false)
	private void taskmanager$addTaskManagerButton(CallbackInfo ci) {
		this.addRenderableWidget(Button.builder(Component.literal("任务管理器"), button ->
			Minecraft.getInstance().gui.setScreen(new TaskManagerScreen())
		).width(204).pos(this.width / 2 - 102, this.height / 4 + 152).build());
	}
}
