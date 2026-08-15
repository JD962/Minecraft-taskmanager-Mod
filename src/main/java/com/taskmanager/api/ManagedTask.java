package com.taskmanager.api;

/**
 * 受管任务（SPI）：可被任务管理器真实地协作式暂停/恢复、终止、调整优先级的后台任务。
 * <p>
 * 与 {@link ProcessAdapter}（进程级自定义逻辑）不同，本接口表示一个拥有真实线程、
 * 能配合「协作式暂停」语义的任务（如模组注册的后台工作线程）。当进程的 target 实现本接口时，
 * 操作引擎会调用这些方法产生真实副作用，而非仅改状态标记。
 */
public interface ManagedTask {
	/**
	 * 协作式暂停/恢复：设置暂停标志，任务自行检查并停止/恢复执行（等价移入待办区）。
	 *
	 * @param paused true 暂停，false 恢复
	 */
	void setPaused(boolean paused);

	/** 请求终止：中断/清理任务线程，任务应配合退出。 */
	void terminate();

	/** 重启：默认先终止再启动（重新创建线程），子类可覆盖为自定义流程。 */
	default void restart() {
		terminate();
	}

	/** 调整优先级：将 1~5 档映射到真实线程优先级（3 档 = Thread.NORM_PRIORITY）。 */
	default void setPriority(int level) {
	}

	/** 查询任务是否仍在运行（用于验证终止是否生效）。 */
	default boolean isRunning() {
		return true;
	}
}
