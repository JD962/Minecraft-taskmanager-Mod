package com.taskmanager.sampling;

import java.lang.management.MemoryMXBean;
import java.lang.management.ManagementFactory;

/**
 * 内存采样：基于 MemoryMXBean 读取堆内/堆外内存占用。
 */
public final class MemorySampler {
	private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();

	/** 堆内存已使用（字节）。 */
	public long heapUsed() {
		return memoryBean.getHeapMemoryUsage().getUsed();
	}

	/** 非堆内存已使用（字节）。 */
	public long nonHeapUsed() {
		return memoryBean.getNonHeapMemoryUsage().getUsed();
	}

	/** 堆内存已提交（字节）：JVM 实际向 OS 申请、对应物理内存 RSS，GC 不回收，数值稳定。 */
	public long heapCommitted() {
		return memoryBean.getHeapMemoryUsage().getCommitted();
	}

	/** 非堆内存已提交（字节）。 */
	public long nonHeapCommitted() {
		return memoryBean.getNonHeapMemoryUsage().getCommitted();
	}
}
