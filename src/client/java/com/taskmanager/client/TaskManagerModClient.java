package com.taskmanager.client;

import com.taskmanager.client.gui.TaskManagerScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

public class TaskManagerModClient implements ClientModInitializer {
	private boolean keyWasDown = false;

	@Override
	public void onInitializeClient() {
		// 按 F4 打开/关闭任务管理器 UI
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			boolean down = GLFW.glfwGetKey(client.getWindow().handle(), GLFW.GLFW_KEY_F4) == GLFW.GLFW_PRESS;
			if (down && !keyWasDown) {
				Minecraft minecraft = Minecraft.getInstance();
				if (minecraft.gui.screen() instanceof TaskManagerScreen) {
					minecraft.gui.setScreen(null);
				} else if (minecraft.gui.screen() == null) {
					minecraft.gui.setScreen(new TaskManagerScreen());
				}
			}
			keyWasDown = down;
		});
	}
}
