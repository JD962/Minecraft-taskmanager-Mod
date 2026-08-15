package com.taskmanager.model;

/**
 * 资源占用快照。不可获取的指标用 NaN（double）或 -1（long）表示，UI 显示为 N/A。
 */
public final class ResourceUsage {
	public static final ResourceUsage EMPTY = new ResourceUsage(Double.NaN, -1L, -1L, Double.NaN);

	/** CPU 使用率（百分比 0~100），NaN 表示不可获取 */
	private final double cpuUsage;
	/** 堆内存占用（字节），-1 表示不可获取 */
	private final long heapMemory;
	/** 非堆内存占用（字节），-1 表示不可获取 */
	private final long nonHeapMemory;
	/** GPU 使用率（百分比 0~100），NaN 表示不可获取 */
	private final double gpuUsage;

	public ResourceUsage(double cpuUsage, long heapMemory, long nonHeapMemory, double gpuUsage) {
		this.cpuUsage = cpuUsage;
		this.heapMemory = heapMemory;
		this.nonHeapMemory = nonHeapMemory;
		this.gpuUsage = gpuUsage;
	}

	public double cpuUsage() {
		return cpuUsage;
	}

	public long heapMemory() {
		return heapMemory;
	}

	public long nonHeapMemory() {
		return nonHeapMemory;
	}

	public double gpuUsage() {
		return gpuUsage;
	}

	/** 总内存（堆 + 非堆），任一不可获取则返回 -1。 */
	public long totalMemory() {
		if (heapMemory < 0 || nonHeapMemory < 0) {
			return -1;
		}
		return heapMemory + nonHeapMemory;
	}

	public boolean isCpuAvailable() {
		return !Double.isNaN(cpuUsage);
	}

	public boolean isMemoryAvailable() {
		return heapMemory >= 0 || nonHeapMemory >= 0;
	}

	public boolean isGpuAvailable() {
		return !Double.isNaN(gpuUsage);
	}

	@Override
	public String toString() {
		return "ResourceUsage{cpu=" + cpuUsage + ", heap=" + heapMemory + ", nonHeap=" + nonHeapMemory + ", gpu=" + gpuUsage + "}";
	}
}
