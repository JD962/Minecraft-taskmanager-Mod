package com.taskmanager.sampling;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.PointerByReference;
import java.util.List;

/**
 * NVIDIA NVML GPU 采样器。基于 JNA 调用 nvml.dll（Windows）/ libnvidia-ml（Linux）。
 * <p>
 * 库加载失败、无 NVIDIA 显卡或采样异常时降级为不可用，{@link #sampleGpuUsage()} 返回 NaN。
 * 实现参考 NVIDIA NVML 官方头文件签名。
 */
public final class NvmlGpuSampler implements GpuSampler {
	private static final int NVML_SUCCESS = 0;

	public interface NvmlLibrary extends Library {
		int nvmlInit_v2();

		int nvmlShutdown();

		int nvmlDeviceGetHandleByIndex_v2(int index, PointerByReference device);

		int nvmlDeviceGetUtilizationRates(Pointer device, NvmlUtilization.ByReference utilization);
	}

	/** 对应 C 结构 nvmlUtilization_t { unsigned int gpu; unsigned int memory; }。 */
	@Structure.FieldOrder({"gpu", "memory"})
	public static class NvmlUtilization extends Structure {
		public int gpu;
		public int memory;

		public static final class ByReference extends NvmlUtilization implements Structure.ByReference {
		}
	}

	private enum State {
		NEW, AVAILABLE, UNSUPPORTED
	}

	private final int deviceIndex;
	private State state = State.NEW;
	private NvmlLibrary nvml;
	private Pointer deviceHandle;

	public NvmlGpuSampler() {
		this(0);
	}

	public NvmlGpuSampler(int deviceIndex) {
		this.deviceIndex = deviceIndex;
	}

	@Override
	public synchronized boolean isAvailable() {
		if (state == State.NEW) {
			init();
		}
		return state == State.AVAILABLE;
	}

	@Override
	public synchronized double sampleGpuUsage() {
		if (!isAvailable() || nvml == null || deviceHandle == null) {
			return Double.NaN;
		}
		try {
			NvmlUtilization.ByReference utilization = new NvmlUtilization.ByReference();
			int result = nvml.nvmlDeviceGetUtilizationRates(deviceHandle, utilization);
			if (result != NVML_SUCCESS) {
				return Double.NaN;
			}
			utilization.read();
			long gpuUsage = Integer.toUnsignedLong(utilization.gpu);
			if (gpuUsage > 100L) {
				return Double.NaN;
			}
			return (double) gpuUsage;
		} catch (RuntimeException | LinkageError e) {
			return Double.NaN;
		}
	}

	private void init() {
		try {
			NvmlLibrary loaded = loadNvmlLibrary();
			if (loaded.nvmlInit_v2() != NVML_SUCCESS) {
				state = State.UNSUPPORTED;
				return;
			}
			PointerByReference ref = new PointerByReference();
			if (loaded.nvmlDeviceGetHandleByIndex_v2(deviceIndex, ref) != NVML_SUCCESS) {
				safeShutdown(loaded);
				state = State.UNSUPPORTED;
				return;
			}
			Pointer handle = ref.getValue();
			if (handle == null || Pointer.nativeValue(handle) == 0L) {
				safeShutdown(loaded);
				state = State.UNSUPPORTED;
				return;
			}
			this.nvml = loaded;
			this.deviceHandle = handle;
			this.state = State.AVAILABLE;
		} catch (RuntimeException | LinkageError e) {
			state = State.UNSUPPORTED;
		}
	}

	/** 释放 NVML（模组关闭时调用）。 */
	public synchronized void close() {
		if (state == State.AVAILABLE && nvml != null) {
			try {
				nvml.nvmlShutdown();
			} catch (RuntimeException | LinkageError ignored) {
			}
		}
		deviceHandle = null;
		nvml = null;
		state = State.UNSUPPORTED;
	}

	private static NvmlLibrary loadNvmlLibrary() {
		List<String> candidates;
		if (Platform.isWindows()) {
			candidates = List.of("nvml");
		} else if (Platform.isLinux()) {
			candidates = List.of("nvidia-ml", "libnvidia-ml.so.1");
		} else {
			throw new UnsupportedOperationException("NVML 仅支持 Windows/Linux");
		}
		LinkageError lastLinkage = null;
		RuntimeException lastRuntime = null;
		for (String candidate : candidates) {
			try {
				return Native.load(candidate, NvmlLibrary.class);
			} catch (LinkageError e) {
				lastLinkage = e;
			} catch (RuntimeException e) {
				lastRuntime = e;
			}
		}
		if (lastLinkage != null) {
			throw lastLinkage;
		}
		if (lastRuntime != null) {
			throw lastRuntime;
		}
		throw new UnsatisfiedLinkError("无法加载 NVML: " + candidates);
	}

	private static void safeShutdown(NvmlLibrary library) {
		try {
			library.nvmlShutdown();
		} catch (RuntimeException | LinkageError ignored) {
		}
	}
}
