package com.taskmanager.api;

/**
 * 可冻结目标（SPI）：能被任务管理器真实地「冻结/解冻」其执行的目标（如服务器 tick 主循环）。
 * <p>
 * 与 {@link ManagedTask}（协作式暂停的后台任务）不同，本接口表示通过平台机制可真实暂停/恢复
 * 执行的目标——例如 Minecraft 原版的 tick 冻结机制（等价 {@code /tick freeze}）。
 * <p>
 * 冻结会轻微影响运行稳定性，因此实现方必须自行做能力探测与失败回退：当底层机制不可用或异常时，
 * {@link #freeze()} / {@link #unfreeze()} 应返回 {@code false} 而非抛出异常，调用方据此回退到逻辑标记。
 */
public interface Freezable {
	/**
	 * 尝试真实冻结，返回是否成功。实现须自行捕获异常（含 {@link LinkageError} 等平台差异），失败返回 false。
	 */
	boolean freeze();

	/**
	 * 尝试真实解冻，返回是否成功。实现须自行捕获异常，失败返回 false。
	 */
	boolean unfreeze();

	/** 当前是否已冻结（用于验证真实生效、以及停机前安全收尾解冻）。 */
	boolean isFrozen();
}
