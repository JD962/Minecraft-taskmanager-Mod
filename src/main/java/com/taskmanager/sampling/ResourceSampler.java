package com.taskmanager.sampling;

import com.taskmanager.core.ProcessManager;
import com.taskmanager.debug.DebugLogger;
import com.taskmanager.model.Process;
import com.taskmanager.model.ProcessCategory;
import com.taskmanager.model.ResourceUsage;
import com.taskmanager.model.ThreadInfo;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
	/** 智能降级阈值：单次采样耗时超过该值（毫秒）时，自动延长采样间隔。 */
	private static final long DEGRADE_THRESHOLD_MS = 200;

	/** 线程名前缀 → 全局进程名 的归类规则。 */
	private static final Map<String, String> THREAD_TO_PROCESS = Map.of(
		"Server thread", "服务端主循环",
		"Render thread", "渲染循环",
		"Netty", "网络 IO"
	);

	private final ThreadSampler threadSampler = new ThreadSampler();
	private final MemorySampler memorySampler = new MemorySampler();
	private final MethodProfiler methodProfiler = MethodProfiler.getInstance();
	private final NidRegistry nidRegistry = new NidRegistry();
	private final ProcessManager processManager = ProcessManager.getInstance();
	private final com.sun.management.OperatingSystemMXBean osBean =
		(com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

	private volatile GpuSampler gpuSampler = null;
	private volatile long intervalMs = DEFAULT_INTERVAL_MS;
	private volatile boolean running = false;
	private volatile Thread worker;

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
		// 方法级采样周期跟随刷新频率（若方法级采样正在运行）
		methodProfiler.setPeriod(MethodProfiler.periodForInterval(this.intervalMs));
	}

	public long intervalMs() {
		return intervalMs;
	}

	public synchronized void start() {
		if (running) {
			return;
		}
		running = true;
		nidRegistry.start();
		worker = new Thread(this::runLoop, "TaskManager-Sampler");
		worker.setDaemon(true);
		worker.setPriority(Thread.MIN_PRIORITY);
		worker.start();
	}

	public synchronized void stop() {
		if (!running) {
			return;
		}
		running = false;
		Thread t = worker;
		worker = null;
		if (t != null) {
			t.interrupt();
			// 等待采样线程真正退出，避免与 GPU 采样器 close 等资源释放产生竞态
			try {
				t.join(2000L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		nidRegistry.stop();
	}

	private void runLoop() {
		int degrade = 0;
		while (running && Thread.currentThread() == worker) {
			long start = System.nanoTime();
			try {
				sample();
			} catch (Exception e) {
				// 采样异常不影响下次采样
			}
			long costMs = (System.nanoTime() - start) / 1_000_000;
			// 智能降级：采样耗时过高时指数延长采样间隔（上限 5s），耗时正常时逐步恢复
			long sleepMs = intervalMs;
			if (costMs > DEGRADE_THRESHOLD_MS) {
				degrade = Math.min(degrade + 1, 3);
				sleepMs = Math.min(intervalMs << degrade, MAX_INTERVAL_MS);
			} else if (degrade > 0) {
				degrade--;
			}
			try {
				Thread.sleep(sleepMs);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	/** 单次采样：线程采集（状态/CPU/分配/堆栈）+ CPU/内存/GPU + 更新进程。 */
	private void sample() {
		Map<Long, ThreadSampler.Snapshot> snapshots = threadSampler.sample();

		// 调试模式：追踪线程创建/销毁
		Map<Long, String> idToName = new HashMap<>();
		for (Map.Entry<Long, ThreadSampler.Snapshot> e : snapshots.entrySet()) {
			idToName.put(e.getKey(), e.getValue().name());
		}
		DebugLogger.getInstance().trackThreadDiff(idToName);

		long heap = memorySampler.heapUsed();
		long nonHeap = memorySampler.nonHeapUsed();
		// 单次读取 gpuSampler，避免 setGpuSampler(null) 并发替换时多次读取产生 NPE
		GpuSampler gpu = gpuSampler;
		double gpuUsage = gpu != null && gpu.isAvailable() ? gpu.sampleGpuUsage() : Double.NaN;

		// 归类线程到全局进程（未匹配的归入「其他线程」）
		Map<String, List<Map.Entry<Long, ThreadSampler.Snapshot>>> processNameToThreads = new HashMap<>();
		for (Map.Entry<Long, ThreadSampler.Snapshot> e : snapshots.entrySet()) {
			processNameToThreads.computeIfAbsent(classifyThread(e.getValue().name()), k -> new ArrayList<>()).add(e);
		}

		for (Process process : processManager.all()) {
			if (process.category() != ProcessCategory.GLOBAL) {
				continue;
			}
			List<Map.Entry<Long, ThreadSampler.Snapshot>> threads = processNameToThreads.getOrDefault(process.name(), List.of());
			process.clearThreads();
			double processCpu = 0;
			for (Map.Entry<Long, ThreadSampler.Snapshot> e : threads) {
				ThreadSampler.Snapshot s = e.getValue();
				double threadCpu = s.cpuPercent();
				if (!Double.isNaN(threadCpu)) {
					processCpu += threadCpu;
				}
				process.addThread(new ThreadInfo(s.name(), e.getKey(), nidRegistry.nidOf(e.getKey()),
					s.state(), s.daemon(), s.priority(),
					s.allocatedBytes(), s.blockedCount(), s.waitedCount(), s.topFrame(),
					new ResourceUsage(threadCpu, -1L, -1L, Double.NaN)));
			}
			// 全局进程内存为整个 JVM 共享，标注到每个全局进程（近似）
			process.setUsage(new ResourceUsage(processCpu, heap, nonHeap, gpuUsage));
		}
	}

	/** 根据线程名归类到全局进程名，未匹配的归入「其他线程」。 */
	private static String classifyThread(String threadName) {
		for (Map.Entry<String, String> entry : THREAD_TO_PROCESS.entrySet()) {
			if (threadName.startsWith(entry.getKey())) {
				return entry.getValue();
			}
		}
		return "其他线程";
	}

	/** 进程级 CPU 负载（百分比 0~100，不可用返回 NaN）。 */
	public double processCpuLoad() {
		double load = osBean.getProcessCpuLoad();
		return load < 0 ? Double.NaN : load * 100.0;
	}

	/** 系统整体 CPU 负载（百分比 0~100，不可用返回 NaN）。 */
	public double systemCpuLoad() {
		double load = osBean.getSystemCpuLoad();
		return load < 0 ? Double.NaN : load * 100.0;
	}

	/** 堆内存已使用（字节），供概览面板使用。 */
	public long heapUsed() {
		return memorySampler.heapUsed();
	}

	/** 非堆内存已使用（字节）。 */
	public long nonHeapUsed() {
		return memorySampler.nonHeapUsed();
	}

	/** 堆内存已提交（字节）：对应物理内存 RSS，数值稳定不随 GC 波动，用于概览展示。 */
	public long heapCommitted() {
		return memorySampler.heapCommitted();
	}

	/** GPU 使用率（百分比 0~100，不可用返回 NaN）。 */
	public double gpuUsage() {
		GpuSampler gpu = gpuSampler;
		return gpu != null && gpu.isAvailable() ? gpu.sampleGpuUsage() : Double.NaN;
	}

	/** 方法级 CPU 快照：线程名 → 方法节点列表（按 CPU 占比降序）。 */
	public Map<String, List<MethodProfiler.MethodNode>> methodSnapshot() {
		return methodProfiler.getSnapshot();
	}
}
