package com.taskmanager.sampling;

/**
 * GPU 采样器接口。实现方（如 NVML/ADL）负责具体的原生库调用与降级。
 */
public interface GpuSampler {
	/** 是否可用（原生库加载与初始化成功）。 */
	boolean isAvailable();

	/** 采样 GPU 使用率（百分比 0~100），不可用返回 NaN。 */
	double sampleGpuUsage();
}
