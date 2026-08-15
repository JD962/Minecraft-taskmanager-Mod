package com.taskmanager.sampling;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.HashMap;
import java.util.Map;

/**
 * CPU 采样：基于 ThreadMXBean 的线程 CPU 时间差分，计算用户态+内核态使用率。
 * <p>
 * 采样线程级 CPU 使用率 = 两次采样间的线程 CPU 时间差 / 墙钟时间差。
 */
public final class CpuSampler {
	private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
	private final boolean supported;

	/** 上次采样的线程 CPU 时间（纳秒），key 为线程 ID。 */
	private final Map<Long, Long> lastCpuTime = new HashMap<>();
	/** 上次采样的墙钟时间（纳秒），key 为线程 ID。 */
	private final Map<Long, Long> lastSampleTime = new HashMap<>();

	public CpuSampler() {
		this.supported = threadBean.isThreadCpuTimeSupported();
		if (supported) {
			threadBean.setThreadCpuTimeEnabled(true);
		}
	}

	public boolean isSupported() {
		return supported;
	}

	/**
	 * 采样一组线程的 CPU 使用率（百分比 0~100，可能 >100 表示多核）。
	 *
	 * @param threadIds 要采样的线程 ID 集合
	 * @return 线程 ID → CPU 使用率；不支持的线程返回 NaN
	 */
	public Map<Long, Double> sampleCpuUsage(long[] threadIds) {
		Map<Long, Double> result = new HashMap<>();
		if (!supported) {
			for (long id : threadIds) {
				result.put(id, Double.NaN);
			}
			return result;
		}
		long now = System.nanoTime();
		for (long id : threadIds) {
			long cpu = threadBean.getThreadCpuTime(id);
			if (cpu < 0) {
				result.put(id, Double.NaN);
				continue;
			}
			Long lastCpu = lastCpuTime.get(id);
			Long lastSample = lastSampleTime.get(id);
			if (lastCpu != null && lastSample != null) {
				long cpuDelta = cpu - lastCpu;
				long timeDelta = now - lastSample;
				if (timeDelta > 0) {
					result.put(id, cpuDelta * 100.0 / timeDelta);
				} else {
					result.put(id, Double.NaN);
				}
			} else {
				result.put(id, Double.NaN);
			}
			lastCpuTime.put(id, cpu);
			lastSampleTime.put(id, now);
		}
		// 清理已消失线程的缓存，防止内存增长
		lastCpuTime.keySet().retainAll(toBoxed(threadIds));
		lastSampleTime.keySet().retainAll(toBoxed(threadIds));
		return result;
	}

	/** 进程级 CPU（关联线程 CPU 之和）。 */
	public double aggregateCpu(Map<Long, Double> threadUsages) {
		double sum = 0;
		for (double usage : threadUsages.values()) {
			if (!Double.isNaN(usage)) {
				sum += usage;
			}
		}
		return sum;
	}

	private static java.util.Set<Long> toBoxed(long[] ids) {
		java.util.Set<Long> set = new java.util.HashSet<>();
		for (long id : ids) {
			set.add(id);
		}
		return set;
	}
}
