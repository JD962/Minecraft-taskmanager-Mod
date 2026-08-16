package com.taskmanager.remote;

import com.taskmanager.core.OperationEngine;
import com.taskmanager.core.ProcessManager;
import com.taskmanager.model.Process;
import com.taskmanager.sampling.ResourceSampler;
import java.util.List;

/**
 * 进程数据提供者实现：桥接 ProcessManager / OperationEngine 与远程协议。
 */
public final class TaskManagerProcessDataProvider implements ProcessDataProvider {
	private static final TaskManagerProcessDataProvider INSTANCE = new TaskManagerProcessDataProvider();

	private TaskManagerProcessDataProvider() {
	}

	public static TaskManagerProcessDataProvider getInstance() {
		return INSTANCE;
	}

	@Override
	public List<ProcessInfo> listProcesses() {
		return ProcessManager.getInstance().all().stream()
			.map(p -> {
				com.taskmanager.model.ResourceUsage u = p.usage();
				double cpu = u.cpuUsage();
				if (!Double.isFinite(cpu) || cpu < 0) {
					cpu = 0.0;
				}
				return new ProcessInfo(
					p.pid(),
					p.name(),
					p.source().id(),
					p.category().name().toLowerCase(java.util.Locale.ROOT),
					p.subCategory(),
					p.side().name().toLowerCase(java.util.Locale.ROOT),
					p.state().name().toLowerCase(java.util.Locale.ROOT),
					cpu,
					u.totalMemory());
			})
			.toList();
	}

	@Override
	public OperationResult operate(ProcessAction action, long pid, String operator) {
		if (pid < 0 || pid > Integer.MAX_VALUE) {
			return OperationResult.fail("invalid pid: " + pid);
		}
		Process process = ProcessManager.getInstance().get((int) pid);
		if (process == null) {
			return OperationResult.fail("process not found: " + pid);
		}
		OperationEngine engine = OperationEngine.getInstance();
		boolean ok = switch (action) {
			case PAUSE -> engine.pause(process, operator);
			case RESUME -> engine.resume(process, operator);
			case TERMINATE -> engine.terminate(process, operator);
			case FORCE_TERMINATE -> engine.forceTerminate(process, operator);
			case RESTART -> engine.restart(process, operator);
			case START -> engine.start(process, operator);
		};
		return ok ? OperationResult.ok() : OperationResult.fail("operation failed");
	}

	@Override
	public OverviewInfo overview() {
		ResourceSampler sampler = ResourceSampler.getInstance();
		TrafficCounter traffic = TrafficCounter.getInstance();
		double gpu = sampler.gpuUsage();
		long diskRead = -1;
		long diskWrite = -1;
		if (sampler.diskIoAvailable()) {
			long[] rate = sampler.diskIoRate();
			if (rate != null && rate.length >= 2) {
				diskRead = rate[0];
				diskWrite = rate[1];
			}
		}
		return new OverviewInfo(
			sampler.processCpuLoad(),
			sampler.systemCpuLoad(),
			sampler.heapUsed(),
			sampler.heapCommitted(),
			gpu,
			traffic.bytesIn(),
			traffic.bytesOut(),
			diskRead,
			diskWrite);
	}
}
