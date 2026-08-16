package com.taskmanager;

import com.taskmanager.command.TaskManagerCommand;
import com.taskmanager.compat.SodiumAdapter;
import com.taskmanager.control.ServerTickControl;
import com.taskmanager.core.ModManager;
import com.taskmanager.core.ProcessManager;
import com.taskmanager.debug.DebugLogger;
import com.taskmanager.debug.PrcExporter;
import com.taskmanager.item.TaskManagerItem;
import com.taskmanager.model.ProcessSide;
import com.taskmanager.model.ProcessSource;
import com.taskmanager.registry.ProcessAdapterRegistry;
import com.taskmanager.remote.RemoteConfig;
import com.taskmanager.remote.TaskManagerProcessDataProvider;
import com.taskmanager.remote.TaskManagerServer;
import com.taskmanager.remote.TaskManagerServerConfig;
import com.taskmanager.sampling.NvmlGpuSampler;
import com.taskmanager.sampling.ResourceSampler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TaskManagerMod implements ModInitializer {
	public static final String MOD_ID = "taskmanager";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** 当前远程管理 token（供 /taskmgr token 命令查看，便于客户端连接）。 */
	public static String remoteToken() {
		return RemoteConfig.token();
	}

	/** 任务管理器终端物品（原版容器 UI 的进入渠道）。注册逻辑抽到 {@link TaskManagerItem#register()}，两端各调用一次。 */
	public static final ResourceKey<Item> TERMINAL_KEY = TaskManagerItem.TERMINAL_KEY;
	public static final Item TASK_MANAGER_TERMINAL = TaskManagerItem.register();

	/** 每个服务器实例一份的 GPU 采样器，随服务器生命周期创建/释放。 */
	private NvmlGpuSampler gpuSampler;

	/** 远程管理服务端（SERVER_STARTED 时创建，端口需跟随游戏端口动态计算）。 */
	private TaskManagerServer remoteServer;

	@Override
	public void onInitialize() {
		LOGGER.info("[任务管理器] 模组已初始化。");

		// 终端物品加入创造模式「工具与实用物品」栏，作为物品互动的进入渠道
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(output ->
			output.prepend(TASK_MANAGER_TERMINAL));

		// 主动适配：Iris / Sodium 等（可选依赖，isModLoaded 保护，缺失时类不会被加载）
		registerCompatAdapters();

		// 实体进程：随实体加载/卸载动态创建/销毁
		ServerEntityEvents.ENTITY_LOAD.register((entity, world) ->
			ProcessManager.getInstance().registerEntity(entity));
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) ->
			ProcessManager.getInstance().unregisterEntity(entity));

		// 全局进程：服务器启动时登记系统级任务（仅服务端进程；客户端进程由客户端模组注册）
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			ProcessManager pm = ProcessManager.getInstance();
			pm.setServer(server);
			// 服务端主循环：带 Freezable 目标，暂停/恢复可真实冻结/解冻服务器 tick（等价 /tick freeze）
			pm.registerGlobal("taskmanager.proc.server_loop", ProcessSource.game(), ProcessSide.SERVER, ServerTickControl.getInstance());
			pm.registerGlobal("taskmanager.proc.network_io", ProcessSource.game(), ProcessSide.SERVER);
			pm.registerGlobal("taskmanager.proc.other_threads", ProcessSource.game(), ProcessSide.SERVER);
			ModManager.getInstance().registerAllMods(pm);
		});

		// 资源采样：每个服务器实例新建 GPU 采样器，避免复用已 close 的实例
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			gpuSampler = new NvmlGpuSampler();
			ResourceSampler sampler = ResourceSampler.getInstance();
			sampler.setGpuSampler(gpuSampler);
			sampler.start();
			LOGGER.info("[任务管理器] GPU 采样器可用性: {}", gpuSampler.isAvailable());
		});

		// 远程管理服务端：启动/停止（配置优先级：系统属性 > config/taskmanager.json > 默认值；
		// 端口未显式配置时跟随游戏端口 + 1，故延迟到 SERVER_STARTED 用 server.getPort() 计算）
		RemoteConfig.load();
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			int remotePort = RemoteConfig.resolvePort(server.getPort());
			remoteServer = new TaskManagerServer(
				new TaskManagerServerConfig(RemoteConfig.host(), remotePort, RemoteConfig.token(), 5, 1 << 20, 4),
				TaskManagerProcessDataProvider.getInstance());
			LOGGER.info("[任务管理器] 远程管理服务端 {}:{} (游戏端口 {}) token 指纹: {}",
				RemoteConfig.host(), remotePort, server.getPort(),
				Integer.toHexString(RemoteConfig.token().hashCode()));
			try {
				remoteServer.start();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				LOGGER.warn("[任务管理器] 远程服务端启动被中断");
			} catch (RuntimeException e) {
				LOGGER.warn("[任务管理器] 远程服务端启动失败: {}", e.getMessage());
			}
		});

		// 服务器停止时清理，逐项隔离异常，避免一处失败导致后续清理跳过
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			safely("远程服务端", () -> {
				if (remoteServer != null) {
					remoteServer.stop();
					remoteServer = null;
				}
			});
			safely("资源采样", () -> ResourceSampler.getInstance().stop());
			safely("GPU 采样器", () -> {
				ResourceSampler.getInstance().setGpuSampler(null);
				if (gpuSampler != null) {
					gpuSampler.close();
					gpuSampler = null;
				}
			});
			safely("进程表", () -> {
				ProcessManager.getInstance().clear();
				ProcessManager.getInstance().setServer(null);
			});
			safely("模组管理", () -> ModManager.getInstance().clear());
			safely("调试日志", () -> DebugLogger.getInstance().disable());
			safely("实时导出", () -> PrcExporter.getInstance().stopRealtime());
		});

		// /taskmgr 命令
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) ->
			TaskManagerCommand.register(dispatcher));

		LOGGER.info("[任务管理器] 事件与命令注册完成。");
	}

	/** 注册对 Sodium 等常用模组的主动适配（可选依赖，缺失时跳过，避免触发类加载）。 */
	private static void registerCompatAdapters() {
		if (FabricLoader.getInstance().isModLoaded("sodium")) {
			try {
				ProcessAdapterRegistry.getInstance().register("sodium", new SodiumAdapter());
				LOGGER.info("[任务管理器] 已注册 Sodium 主动适配（渲染核心保护）。");
			} catch (LinkageError e) {
				LOGGER.warn("[任务管理器] Sodium API 不兼容，跳过主动适配", e);
			}
		}
	}

	private static void safely(String what, Runnable action) {
		try {
			action.run();
		} catch (Throwable t) {
			LOGGER.error("[任务管理器] 清理 {} 失败", what, t);
		}
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
