package com.taskmanager.client.bridge;

/**
 * 客户端桥接：common 代码无法直接引用 client-only 类，通过此桥接触发打开 GUI。
 */
public final class TaskManagerClientBridge {
	private static volatile Runnable opener;

	private TaskManagerClientBridge() {
	}

	/** 由客户端入口注册打开逻辑。 */
	public static void setOpener(Runnable runnable) {
		opener = runnable;
	}

	/** 触发打开任务管理器 GUI（未注册时静默忽略）。 */
	public static void open() {
		Runnable r = opener;
		if (r != null) {
			r.run();
		}
	}
}
