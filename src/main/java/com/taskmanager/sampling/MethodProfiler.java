package com.taskmanager.sampling;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingStream;

/**
 * 方法级 CPU 采样：基于 JFR 的 jdk.ExecutionSample 事件，聚合每个线程内各方法的独占 CPU 占比。
 * 纯 JDK 实现，无 JNI，开销极低。
 * <p>
 * 采用「独占式」统计：每个样本只统计栈顶的 Java 方法，得到「线程 → 方法 → CPU 占比」。
 * 采样周期跟随 UI 刷新频率（见 {@link #periodForInterval}），且仅在 UI 可见时由 UI 调用
 * {@link #start}/{@link #stop} 启停，UI 关闭即停止捕获，不影响 /taskmgr 命令。
 */
public final class MethodProfiler {
	private static final MethodProfiler INSTANCE = new MethodProfiler();

	/** 方法级采样周期下限（保证方法级数据仍有统计意义）。 */
	private static final long MIN_PERIOD_MS = 10;
	/** 方法级采样周期上限（刷新频率极慢时的最低采样开销）。 */
	private static final long MAX_PERIOD_MS = 100;
	/** 采样线程数上限：防止动态线程名导致键无界增长。 */
	private static final int MAX_THREADS = 256;
	/** 每个线程的方法条目上限：防止 lambda/隐藏类等动态类名导致键无界增长。 */
	private static final int MAX_METHODS_PER_THREAD = 2048;

	private final ConcurrentMap<String, ConcurrentMap<String, Long>> methodCounts = new ConcurrentHashMap<>();
	private final ConcurrentMap<String, Long> threadTotal = new ConcurrentHashMap<>();
	private volatile RecordingStream stream;
	private volatile boolean running;
	private volatile long periodMs = MIN_PERIOD_MS;
	/** 采样代际：区分新旧 stream，旧 stream 的在途回调据此失效。 */
	private volatile long generation = 0L;

	private MethodProfiler() {
	}

	public static MethodProfiler getInstance() {
		return INSTANCE;
	}

	/** 根据 UI 刷新频率（采样间隔）映射方法级采样周期：刷新越慢采样越省，但保持 10~100ms 精度。 */
	public static long periodForInterval(long intervalMs) {
		return Math.clamp(intervalMs / 100, MIN_PERIOD_MS, MAX_PERIOD_MS);
	}

	/** 启动采样。 */
	public synchronized void start(long periodMs) {
		if (running) {
			return;
		}
		long p = Math.clamp(periodMs, MIN_PERIOD_MS, MAX_PERIOD_MS);
		methodCounts.clear();
		threadTotal.clear();
		long gen = ++generation;
		RecordingStream next = createStream(p, gen);
		if (next == null) {
			return;
		}
		this.stream = next;
		this.periodMs = p;
		this.running = true;
	}

	public synchronized void stop() {
		if (!running) {
			return;
		}
		running = false;
		generation++;
		RecordingStream s = stream;
		stream = null;
		if (s != null) {
			s.close();
		}
		// 清空采样数据，避免停止后 getSnapshot() 返回过期快照
		methodCounts.clear();
		threadTotal.clear();
	}

	/** 运行中调整采样周期：重启 JFR stream（新周期）；周期变化导致样本权重不一致，故清空累积数据。 */
	public synchronized void setPeriod(long periodMs) {
		if (!running) {
			return;
		}
		long p = Math.clamp(periodMs, MIN_PERIOD_MS, MAX_PERIOD_MS);
		if (this.periodMs == p) {
			return;
		}
		long gen = ++generation;
		RecordingStream next = createStream(p, gen);
		if (next == null) {
			return;
		}
		RecordingStream old = stream;
		this.stream = next;
		this.periodMs = p;
		if (old != null) {
			old.close();
		}
		methodCounts.clear();
		threadTotal.clear();
	}

	public boolean isRunning() {
		return running;
	}

	/** 创建并启动 stream；启动失败时关闭并返回 null（避免 JFR 资源泄漏）。 */
	private RecordingStream createStream(long periodMs, long gen) {
		RecordingStream s = new RecordingStream();
		try {
			s.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(periodMs));
			s.onEvent("jdk.ExecutionSample", event -> onSample(event, gen));
			s.startAsync();
			return s;
		} catch (RuntimeException | Error e) {
			s.close();
			return null;
		}
	}

	private void onSample(RecordedEvent event, long gen) {
		if (!running || gen != generation) {
			return;
		}
		try {
			RecordedThread thread = event.getThread();
			if (thread == null) {
				return;
			}
			String threadName = thread.getJavaName();
			if (threadName == null) {
				threadName = "thread-" + thread.getJavaThreadId();
			}
			RecordedStackTrace stackTrace = event.getStackTrace();
			if (stackTrace == null) {
				return;
			}
			List<RecordedFrame> frames = stackTrace.getFrames();
			// 独占式 CPU 占比：只统计栈顶的 Java 方法
			RecordedFrame top = null;
			for (RecordedFrame frame : frames) {
				if (frame.isJavaFrame()) {
					top = frame;
					break;
				}
			}
			if (top == null) {
				return;
			}
			RecordedMethod method = top.getMethod();
			String key = method.getType().getName() + "." + method.getName();
			// 上限保护：动态线程名 / lambda / 隐藏类名会导致键无界增长，超过上限拒绝新键
			if (methodCounts.size() >= MAX_THREADS && !methodCounts.containsKey(threadName)) {
				return;
			}
			ConcurrentMap<String, Long> counts = methodCounts.computeIfAbsent(threadName, k -> new ConcurrentHashMap<>());
			if (counts.size() >= MAX_METHODS_PER_THREAD && !counts.containsKey(key)) {
				return;
			}
			counts.merge(key, 1L, Long::sum);
			threadTotal.merge(threadName, 1L, Long::sum);
		} catch (Exception ignored) {
			// 采样异常不影响整体
		}
	}

	/** 获取当前快照：线程名 → 方法节点列表（按 CPU 占比降序）。 */
	public Map<String, List<MethodNode>> getSnapshot() {
		Map<String, List<MethodNode>> snapshot = new HashMap<>();
		for (Map.Entry<String, ConcurrentMap<String, Long>> entry : methodCounts.entrySet()) {
			String threadName = entry.getKey();
			ConcurrentMap<String, Long> counts = entry.getValue();
			Long total = threadTotal.get(threadName);
			if (total == null || total == 0) {
				continue;
			}
			List<MethodNode> nodes = new ArrayList<>();
			for (Map.Entry<String, Long> methodEntry : counts.entrySet()) {
				double ratio = methodEntry.getValue() * 100.0 / total;
				nodes.add(new MethodNode(methodEntry.getKey(), methodEntry.getValue(), ratio));
			}
			nodes.sort(Comparator.comparingDouble(MethodNode::cpuRatio).reversed());
			snapshot.put(threadName, nodes);
		}
		return snapshot;
	}

	/** 方法节点：方法名 + 命中次数 + 该线程内 CPU 占比（百分比）。 */
	public record MethodNode(String methodName, long hitCount, double cpuRatio) {
	}
}
