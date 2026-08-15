package com.taskmanager.sampling;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.HashMap;
import java.util.Map;

/**
 * 线程采样器：基于 ThreadMXBean 批量采集线程的状态、守护标记、优先级、累计分配字节、
 * 栈顶帧与 CPU 使用率（线程 CPU 时间差分）。
 * <p>
 * CPU 使用率 = 两次采样间线程 CPU 时间差 / 墙钟时间差；首次采样无基线返回 NaN（采样中）。
 */
public final class ThreadSampler {
	/** 堆栈抓取深度：只需栈顶帧即可，降低每秒采样的开销。 */
	private static final int MAX_STACK_DEPTH = 8;

	private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
	private final com.sun.management.ThreadMXBean allocBean;
	private final boolean cpuSupported;
	private final boolean allocSupported;

	/** 上次采样的线程 CPU 时间（纳秒）。 */
	private final Map<Long, Long> lastCpuTime = new HashMap<>();
	/** 上次采样的墙钟时间（纳秒）。 */
	private final Map<Long, Long> lastWallTime = new HashMap<>();

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

	/** 单次采样结果。 */
	public record Snapshot(String name, Thread.State state, boolean daemon, int priority,
	                       long allocatedBytes, String topFrame, double cpuPercent) {
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
			String topFrame = topFrame(info.getStackTrace());
			result.put(id, new Snapshot(info.getThreadName(), info.getThreadState(), info.isDaemon(),
				info.getPriority(), allocated, topFrame, cpuPercent));
		}
		// 清理已消失线程的 CPU 缓存，防止内存增长
		lastCpuTime.keySet().retainAll(result.keySet());
		lastWallTime.keySet().retainAll(result.keySet());
		return result;
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
		return cpuDelta * 100.0 / wallDelta;
	}

	private static String topFrame(StackTraceElement[] stack) {
		if (stack == null || stack.length == 0) {
			return null;
		}
		StackTraceElement e = stack[0];
		return e.getClassName() + "." + e.getMethodName();
	}
}
