package com.taskmanager;

import com.taskmanager.command.TaskManagerCommand;
import com.taskmanager.core.ProcessManager;
import com.taskmanager.item.TaskManagerItem;
import com.taskmanager.model.ProcessSource;
import com.taskmanager.sampling.NvmlGpuSampler;
import com.taskmanager.sampling.ResourceSampler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TaskManagerMod implements ModInitializer {
	public static final String MOD_ID = "taskmanager";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** 任务管理器终端物品（原版容器 UI 的进入渠道）。 */
	public static final ResourceKey<Item> TERMINAL_KEY = ResourceKey.create(Registries.ITEM, id("task_manager_terminal"));
	public static final Item TASK_MANAGER_TERMINAL = Registry.register(
		BuiltInRegistries.ITEM, TERMINAL_KEY, new TaskManagerItem(new Item.Properties().setId(TERMINAL_KEY)));

	@Override
	public void onInitialize() {
		LOGGER.info("[任务管理器] 模组已初始化。");

		// 实体进程：随实体加载/卸载动态创建/销毁
		ServerEntityEvents.ENTITY_LOAD.register((entity, world) ->
			ProcessManager.getInstance().registerEntity(entity));
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) ->
			ProcessManager.getInstance().unregisterEntity(entity));

		// 全局进程：服务器启动时登记系统级任务
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			ProcessManager pm = ProcessManager.getInstance();
			pm.registerGlobal("服务端主循环", ProcessSource.game());
			pm.registerGlobal("渲染循环", ProcessSource.game());
			pm.registerGlobal("网络 IO", ProcessSource.game());
			pm.registerGlobal("其他线程", ProcessSource.game());
		});

		// 资源采样：接入 GPU 采样器并启动（M4 UI 接入后改为 UI 可见才启用）
		NvmlGpuSampler gpuSampler = new NvmlGpuSampler();
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			ResourceSampler sampler = ResourceSampler.getInstance();
			sampler.setGpuSampler(gpuSampler);
			sampler.start();
			LOGGER.info("[任务管理器] GPU 采样器可用性: {}", gpuSampler.isAvailable());
		});

		// 服务器停止时停止采样、释放 GPU、清空进程表
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			ResourceSampler.getInstance().stop();
			gpuSampler.close();
			ProcessManager.getInstance().clear();
		});

		// /taskmgr 命令
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) ->
			TaskManagerCommand.register(dispatcher));

		LOGGER.info("[任务管理器] 事件与命令注册完成。");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
