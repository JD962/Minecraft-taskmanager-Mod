package com.taskmanager.control;

import com.taskmanager.api.Freezable;
import com.taskmanager.core.ProcessManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.TickRateManager;

/**
 * 服务器 tick 主循环的冻结控制器：通过原版 tick 冻结机制（等价 {@code /tick freeze}）真实暂停/恢复世界 tick。
 * <p>
 * 带「能力探测 + 失败回退」余量：冻结会轻微影响稳定性，因此每次访问都先判空服务器与 tick 管理器，
 * 并对所有异常（含 {@link NoSuchMethodError}/{@link NoClassDefFoundError} 等平台差异）捕获并返回 {@code false}，
 * 由操作引擎回退到逻辑标记，绝不因此崩溃或假装成功。
 * <p>
 * 无状态单例：始终通过 {@link ProcessManager#server()} 动态读取当前服务器，与具体服务器实例解耦；
 * 单例的静态字段提供强引用，避免作为进程 target（弱引用存储）时被 GC 回收。
 */
public final class ServerTickControl implements Freezable {
	private static final ServerTickControl INSTANCE = new ServerTickControl();

	private ServerTickControl() {
	}

	public static ServerTickControl getInstance() {
		return INSTANCE;
	}

	@Override
	public boolean freeze() {
		TickRateManager manager = tickManager();
		if (manager == null) {
			return false;
		}
		// 多人专用服务端：禁止冻结世界 tick，否则影响其他玩家游玩；仅单人集成服务器可冻结
		MinecraftServer server = ProcessManager.getInstance().server();
		if (server != null && server.isDedicatedServer()) {
			return false;
		}
		try {
			manager.setFrozen(true);
			// 以真实状态为准，避免 setFrozen 被静默吞掉时误报成功
			return manager.isFrozen();
		} catch (Throwable t) {
			return false;
		}
	}

	@Override
	public boolean unfreeze() {
		TickRateManager manager = tickManager();
		if (manager == null) {
			return false;
		}
		try {
			manager.setFrozen(false);
			return !manager.isFrozen();
		} catch (Throwable t) {
			return false;
		}
	}

	@Override
	public boolean isFrozen() {
		TickRateManager manager = tickManager();
		return manager != null && manager.isFrozen();
	}

	/** 能力探测：服务器与其 tick 管理器可达时才算可用（兼容性与回退的依据）。 */
	public boolean supportsFreeze() {
		return tickManager() != null;
	}

	private static TickRateManager tickManager() {
		MinecraftServer server = ProcessManager.getInstance().server();
		return server == null ? null : server.tickRateManager();
	}
}
