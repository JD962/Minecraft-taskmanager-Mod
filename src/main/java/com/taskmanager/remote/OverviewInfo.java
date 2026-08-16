package com.taskmanager.remote;

/**
 * 服务端系统资源概览快照，随 list 响应下发，供客户端远程视图展示概览指标。
 * <p>
 * 数值语义：CPU/GPU 为百分比（0-100，NaN 表示不可用）；堆内存为字节；网络为累计字节数；
 * 磁盘 I/O 为速率（字节/秒），{@code -1} 表示不支持。
 */
public record OverviewInfo(
	double processCpu, double systemCpu,
	long heapUsed, long heapCommitted, double gpuUsage,
	long netIn, long netOut, long diskReadRate, long diskWriteRate
) {
	public static final OverviewInfo EMPTY = new OverviewInfo(
		Double.NaN, Double.NaN, -1, -1, Double.NaN, -1, -1, -1, -1);
}
