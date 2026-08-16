package com.taskmanager.client;

import com.taskmanager.client.bridge.TaskManagerClientBridge;
import com.taskmanager.client.compat.IrisAdapter;
import com.taskmanager.client.gui.TaskManagerScreen;
import com.taskmanager.client.remote.RemoteInstances;
import com.taskmanager.core.ProcessManager;
import com.taskmanager.item.TaskManagerItem;
import com.taskmanager.model.ProcessSide;
import com.taskmanager.model.ProcessSource;
import com.taskmanager.registry.ProcessAdapterRegistry;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

public class TaskManagerModClient implements ClientModInitializer {
	private boolean keyWasDown = false;

	@Override
	public void onInitializeClient() {
		// 拆分模组：客户端也注册终端物品，保证客户端注册表与服务端一致
		TaskManagerItem.register();

		// 加载已保存的远程实例（config/taskmanager-client.json），重启后保留
		RemoteInstances.getInstance().loadSaved();

		// 拆分模组：客户端进程（渲染循环）由客户端模组注册，纯客户端也能看到客户端页签
		ProcessManager.getInstance().registerGlobal("taskmanager.proc.render_loop", ProcessSource.game(), ProcessSide.CLIENT);

		// 原版容器 UI 进入渠道：终端物品右键触发此桥接，打开任务管理器 GUI
		TaskManagerClientBridge.setOpener(() ->
			Minecraft.getInstance().gui.setScreen(new TaskManagerScreen()));

		// 主动适配：Iris（客户端模组，isModLoaded 保护 + LinkageError 隔离）
		if (FabricLoader.getInstance().isModLoaded("iris")) {
			try {
				ProcessAdapterRegistry.getInstance().register("iris", new IrisAdapter());
			} catch (LinkageError e) {
				// Iris 版本不兼容，跳过主动适配
			}
		}

		// 快捷键：按 F12 打开/关闭任务管理器 UI（可选快捷方式）
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			boolean down = GLFW.glfwGetKey(client.getWindow().handle(), GLFW.GLFW_KEY_F12) == GLFW.GLFW_PRESS;
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


