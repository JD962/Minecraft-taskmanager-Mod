package com.taskmanager.sampling;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.HashMap;
import java.util.Map;

/**
 * 线程采样器：基于 ThreadMXBean 批量采集线程的状态、守护标记、优先级、累计分配字节、
 * 栈顶帧、锁竞争/等待次数与 CPU 使用率（线程 CPU 时间差分 + 指数平滑）。
 * <p>
 * CPU 使用率 = 两次采样间线程 CPU 时间差 / 墙钟时间差；首次采样无基线返回 NaN（采样中）。
 * 单次差分噪声大，故用指数移动平均（EMA）做「少量多次采样取平均」，得到平滑数值。
 * 采样周期由 {@link ResourceSampler} 驱动，与 UI 刷新频率一致（3 档：0.5s / 1s / 5s）。
 */
public final class ThreadSampler {
	/** 堆栈抓取深度：只需栈顶帧即可，降低每秒采样的开销。 */
	private static final int MAX_STACK_DEPTH = 8;
	/** CPU 平滑系数：越大越灵敏、越小越平滑（等价于多次采样的加权平均）。 */
	private static final double CPU_ALPHA = 0.3;

	private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
	private final com.sun.management.ThreadMXBean allocBean;
	private final boolean cpuSupported;
	private final boolean allocSupported;

	/** 上次采样的线程 CPU 时间（纳秒）。 */
	private final Map<Long, Long> lastCpuTime = new HashMap<>();
	/** 上次采样的墙钟时间（纳秒）。 */
	private final Map<Long, Long> lastWallTime = new HashMap<>();
	/** CPU 平滑值（EMA），消除单次差分噪声。 */
	private final Map<Long, Double> smoothedCpu = new HashMap<>();
	/** 上次采样的累计分配字节（用于算分配速率）。 */
	private final Map<Long, Long> lastAllocated = new HashMap<>();
	/** 上次采样分配的时间（纳秒）。 */
	private final Map<Long, Long> lastAllocTime = new HashMap<>();

	public ThreadSampler() {
		this.cpuSupported = threadBean.isThreadCpuTimeSupported();
		if (cpuSupported) {
			threadBean.setThreadCpuTimeEnabled(true);
		}
		this.allocBean = threadBean instanceof com.sun.management.ThreadMXBean b ? b : null;
		this.allocSupported = allocBean != null && allocBean.isThreadAllocatedMemorySupported();
		if (allocSupported) {
			allocBean.setThreadAllocatedMemoryEnabled(true);
		}
	}

	/** 单次采样结果。allocRate 为分配速率（字节/秒，-1 表示无基线或不支持）。 */
	public record Snapshot(String name, Thread.State state, boolean daemon, int priority,
	                       long allocatedBytes, long allocRate, String topFrame, double cpuPercent,
	                       long blockedCount, long waitedCount) {
	}

	/**
	 * 采样当前所有线程。
	 *
	 * @return 线程 ID → 采样快照
	 */
	public Map<Long, Snapshot> sample() {
		Map<Long, Snapshot> result = new HashMap<>();
		long[] ids = threadBean.getAllThreadIds();
		long now = System.nanoTime();

		java.lang.management.ThreadInfo[] infos = threadBean.getThreadInfo(ids, false, false, MAX_STACK_DEPTH);
		for (int i = 0; i < ids.length; i++) {
			long id = ids[i];
			java.lang.management.ThreadInfo info = infos[i];
			if (info == null) {
				continue; // 采样期间线程已结束
			}
			long cpuNanos = threadBean.getThreadCpuTime(id);
			double cpuPercent = diffCpu(id, cpuNanos, now);
			long allocated = allocSupported ? allocBean.getThreadAllocatedBytes(id) : -1L;
			long allocRate = diffAlloc(id, allocated, now);
			String topFrame = topFrame(info.getStackTrace());
			result.put(id, new Snapshot(info.getThreadName(), info.getThreadState(), info.isDaemon(),
				info.getPriority(), allocated, allocRate, topFrame, cpuPercent,
				info.getBlockedCount(), info.getWaitedCount()));
		}
		// 清理已消失线程的缓存，防止内存增长
		lastCpuTime.keySet().retainAll(result.keySet());
		lastWallTime.keySet().retainAll(result.keySet());
		smoothedCpu.keySet().retainAll(result.keySet());
		lastAllocated.keySet().retainAll(result.keySet());
		lastAllocTime.keySet().retainAll(result.keySet());
		return result;
	}

	/** 分配速率（字节/秒）：两次采样累计分配差 / 时间差；首次采样无基线返回 -1。 */
	private long diffAlloc(long id, long allocated, long nowNanos) {
		if (!allocSupported || allocated < 0) {
			return -1L;
		}
		Long last = lastAllocated.get(id);
		Long lastTime = lastAllocTime.get(id);
		lastAllocated.put(id, allocated);
		lastAllocTime.put(id, nowNanos);
		if (last == null || lastTime == null) {
			return -1L; // 首次采样，无基线
		}
		long delta = allocated - last;
		long timeDelta = nowNanos - lastTime;
		if (timeDelta <= 0 || delta < 0) {
			return -1L;
		}
		return delta * 1_000_000_000L / timeDelta;
	}

	private double diffCpu(long id, long cpuNanos, long nowNanos) {
		if (!cpuSupported || cpuNanos < 0) {
			return Double.NaN;
		}
		Long lastCpu = lastCpuTime.get(id);
		Long lastWall = lastWallTime.get(id);
		lastCpuTime.put(id, cpuNanos);
		lastWallTime.put(id, nowNanos);
		if (lastCpu == null || lastWall == null) {
			return Double.NaN; // 首次采样，无基线
		}
		long cpuDelta = cpuNanos - lastCpu;
		long wallDelta = nowNanos - lastWall;
		if (wallDelta <= 0 || cpuDelta < 0) {
			return Double.NaN;
		}
		double current = cpuDelta * 100.0 / wallDelta;
		// 指数移动平均：少量多次采样取平均，消除单次差分噪声，得到平滑 CPU
		double prev = smoothedCpu.getOrDefault(id, current);
		double smoothed = CPU_ALPHA * current + (1.0 - CPU_ALPHA) * prev;
		smoothedCpu.put(id, smoothed);
		return smoothed;
	}

	private static String topFrame(StackTraceElement[] stack) {
		if (stack == null || stack.length == 0) {
			return null;
		}
		StackTraceElement e = stack[0];
		return e.getClassName() + "." + e.getMethodName();
	}
}
