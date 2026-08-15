package com.taskmanager.sampling;

import com.taskmanager.core.ProcessManager;
import com.taskmanager.model.Process;
import com.taskmanager.model.ProcessCategory;
import com.taskmanager.model.ResourceUsage;
import com.taskmanager.model.ThreadInfo;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 资源采样调度器：低优先级后台线程定时采样，更新进程的线程列表与资源占用。
 * <p>
 * 默认采样周期 1s（0.5~5s 可调），UI 可见时才应调用 {@link #start()} 启用采样。
 */
public final class ResourceSampler {
	private static final ResourceSampler INSTANCE = new ResourceSampler();

	private static final long DEFAULT_INTERVAL_MS = 1000;
	private static final long MIN_INTERVAL_MS = 500;
	private static final long MAX_INTERVAL_MS = 5000;

	/** 线程名前缀 → 全局进程名 的归类规则。 */
	private static final Map<String, String> THREAD_TO_PROCESS = Map.of(
		"Server thread", "服务端主循环",
		"Render thread", "渲染循环",
		"Netty", "网络 IO"
	);

	private final CpuSampler cpuSampler = new CpuSampler();
	private final MemorySampler memorySampler = new MemorySampler();
	private final ProcessManager processManager = ProcessManager.getInstance();
	private final com.sun.management.OperatingSystemMXBean osBean =
		(com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

	private volatile GpuSampler gpuSampler = null;
	private volatile long intervalMs = DEFAULT_INTERVAL_MS;
	private volatile boolean running = false;
	private Thread worker;

	// 复用对象，避免频繁分配
	private final List<ThreadInfo> threadBuffer = new ArrayList<>();

	private ResourceSampler() {
	}

	public static ResourceSampler getInstance() {
		return INSTANCE;
	}

	public void setGpuSampler(GpuSampler sampler) {
		this.gpuSampler = sampler;
	}

	public void setInterval(long ms) {
		this.intervalMs = Math.clamp(ms, MIN_INTERVAL_MS, MAX_INTERVAL_MS);
	}

	public long intervalMs() {
		return intervalMs;
	}

	public synchronized void start() {
		if (running) {
			return;
		}
		running = true;
		worker = new Thread(this::runLoop, "TaskManager-Sampler");
		worker.setDaemon(true);
		worker.setPriority(Thread.MIN_PRIORITY);
		worker.start();
	}

	public synchronized void stop() {
		running = false;
		if (worker != null) {
			worker.interrupt();
			worker = null;
		}
	}

	private void runLoop() {
		while (running) {
			try {
				sample();
			} catch (Exception e) {
				// 采样异常不影响下次采样
			}
			try {
				Thread.sleep(intervalMs);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	/** 单次采样：线程采集 + CPU/内存/GPU + 更新进程。 */
	private void sample() {
		Set<Thread> allThreads = Thread.getAllStackTraces().keySet();
		long[] threadIds = allThreads.stream().mapToLong(Thread::threadId).toArray();

		Map<Long, Double> cpu = cpuSampler.sampleCpuUsage(threadIds);
		long heap = memorySampler.heapUsed();
		long nonHeap = memorySampler.nonHeapUsed();
		double gpu = gpuSampler != null && gpuSampler.isAvailable() ? gpuSampler.sampleGpuUsage() : Double.NaN;

		// 归类线程到全局进程
		Map<Long, Thread> idToThread = new HashMap<>();
		for (Thread t : allThreads) {
			idToThread.put(t.threadId(), t);
		}

		for (Process process : processManager.all()) {
			if (process.category() != ProcessCategory.GLOBAL) {
				continue;
			}
			threadBuffer.clear();
			double processCpu = 0;
			for (Map.Entry<Long, Thread> entry : idToThread.entrySet()) {
				Thread thread = entry.getValue();
				if (!belongsTo(thread.getName(), process.name())) {
					continue;
				}
				double threadCpu = cpu.getOrDefault(entry.getKey(), Double.NaN);
				if (!Double.isNaN(threadCpu)) {
					processCpu += threadCpu;
				}
				threadBuffer.add(new ThreadInfo(thread.getName(), thread.threadId(), -1L,
					new ResourceUsage(threadCpu, -1L, -1L, Double.NaN)));
			}
			process.clearThreads();
			for (ThreadInfo ti : threadBuffer) {
				process.addThread(ti);
			}
			// 全局进程内存为整个 JVM 共享，标注到每个全局进程（近似）
			process.setUsage(new ResourceUsage(processCpu, heap, nonHeap, gpu));
		}
	}

	/** 线程是否归属某全局进程。 */
	private static boolean belongsTo(String threadName, String processName) {
		for (Map.Entry<String, String> entry : THREAD_TO_PROCESS.entrySet()) {
			if (threadName.startsWith(entry.getKey()) && entry.getValue().equals(processName)) {
				return true;
			}
		}
		return false;
	}

	/** 进程级 CPU 负载（0~1，不可用返回 NaN）。 */
	public double processCpuLoad() {
		double load = osBean.getProcessCpuLoad();
		return load < 0 ? Double.NaN : load * 100.0;
	}
}
