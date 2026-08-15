package com.taskmanager.api;

import com.taskmanager.model.Process;

/**
 * 进程适配接口（SPI）。
 * <p>
 * 模组/游戏可通过实现本接口，为特定进程提供自定义的暂停/恢复/重启/优先级/启动逻辑。
 * 所有方法均有默认实现（空操作），按需覆盖即可。
 */
public interface ProcessAdapter {
	/** 最低优先级档位 */
	int MIN_PRIORITY = 1;
	/** 默认标准优先级档位（对应 Java Thread.NORM_PRIORITY） */
	int DEFAULT_PRIORITY = 3;
	/** 最高优先级档位 */
	int MAX_PRIORITY = 5;

	/**
	 * 声明进程是否可终止。返回 false 表示该进程受保护（类似内核保护），
	 * 除强制终止外不可被普通终止操作关闭。
	 */
	default boolean isTerminable() {
		return true;
	}

	/** 自定义暂停逻辑（默认实现由引擎回退为逻辑暂停）。 */
	default void onPause(Process process) {
	}

	/** 自定义恢复逻辑。 */
	default void onResume(Process process) {
	}

	/** 自定义重启逻辑（默认先终止再启动）。 */
	default void onRestart(Process process) {
	}

	/** 自定义优先级调整逻辑（1~5 档，3 为默认）。 */
	default void onSetPriority(Process process, int level) {
	}

	/** 自定义启动逻辑。 */
	default void onStart(Process process) {
	}
}
