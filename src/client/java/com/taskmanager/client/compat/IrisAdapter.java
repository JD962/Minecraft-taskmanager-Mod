package com.taskmanager.client.compat;

import com.taskmanager.TaskManagerMod;
import com.taskmanager.api.ProcessAdapter;
import com.taskmanager.model.Process;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.api.v0.IrisApiConfig;
import net.minecraft.client.Minecraft;

/**
 * Iris 主动适配（客户端侧）：把「重启」操作映射为「重新应用光影（近似重载）」。
 * <p>
 * 注意：26.2 的 Iris API 为 v0，已移除旧版的 {@code IrisApi.reload()}，仅保留
 * {@code setShadersEnabledAndApply(boolean)}，故用「关 → 开」重新应用光影作为重载的近似实现。
 * <p>
 * 光影操作需在客户端线程执行，故通过 {@link Minecraft#execute(Runnable)} 调度。
 * 本类仅在 Iris 已加载时被实例化（客户端入口用 isModLoaded 保护），Iris 缺失时不会触发类加载。
 */
public final class IrisAdapter implements ProcessAdapter {
	@Override
	public void onRestart(Process process) {
		Minecraft client = Minecraft.getInstance();
		if (client == null) {
			return;
		}
		// 光影操作必须在客户端线程执行
		client.execute(this::reloadShaders);
	}

	private void reloadShaders() {
		try {
			IrisApiConfig config = IrisApi.getInstance().getConfig();
			if (config == null || !config.areShadersEnabled()) {
				// 光影未开启：保持现状，不擅自开启
				return;
			}
			// 已开启：关闭再开启，重新加载/应用光影（近似 reload）
			config.setShadersEnabledAndApply(false);
			try {
				config.setShadersEnabledAndApply(true);
			} catch (Throwable applyError) {
				// 重新开启失败：尽力恢复原开启状态
				try {
					config.setShadersEnabledAndApply(true);
				} catch (Throwable restoreError) {
					applyError.addSuppressed(restoreError);
				}
				TaskManagerMod.LOGGER.warn("[任务管理器] Iris 光影重新应用失败", applyError);
			}
		} catch (Throwable e) {
			TaskManagerMod.LOGGER.warn("[任务管理器] Iris 主动适配执行异常", e);
		}
	}
}
