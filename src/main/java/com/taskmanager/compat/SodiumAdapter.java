package com.taskmanager.compat;

import com.taskmanager.api.ProcessAdapter;

/**
 * Sodium 主动适配：Sodium 是渲染核心，声明其进程不可终止（避免误终止导致渲染崩溃）。
 * <p>
 * Sodium 无公开的运行时 Java API 可调用，故仅提供「不可终止」保护；其余操作走默认逻辑。
 * <p>
 * 本类仅在 Sodium 已加载时被实例化（入口用 isModLoaded 保护）。
 */
public final class SodiumAdapter implements ProcessAdapter {
	@Override
	public boolean isTerminable() {
		return false;
	}
}
