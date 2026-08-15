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
 * 方法级 CPU 采样：基于 JFR 的 jdk.ExecutionSample 事件，聚合每个线程内各方法的命中次数，
 * 得到「线程 → 方法 → CPU 占比」。纯 JDK 实现，无 JNI，开销极低。
 */
public final class MethodProfiler {
	private final ConcurrentMap<String, ConcurrentMap<String, Long>> methodCounts = new ConcurrentHashMap<>();
	private final ConcurrentMap<String, Long> threadTotal = new ConcurrentHashMap<>();
	private volatile RecordingStream stream;
	private volatile boolean running;

	/** 方法节点：方法名 + 命中次数 + 该线程内 CPU 占比（百分比）。 */
	public record MethodNode(String methodName, long hitCount, double cpuRatio) {
	}

	/** 启动采样。 */
	public synchronized void start(long periodMs) {
		if (running) {
			return;
		}
		methodCounts.clear();
		threadTotal.clear();
		RecordingStream recordingStream = new RecordingStream();
		recordingStream.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(periodMs));
		recordingStream.onEvent("jdk.ExecutionSample", this::onSample);
		recordingStream.startAsync();
		this.stream = recordingStream;
		this.running = true;
	}

	public synchronized void stop() {
		if (!running) {
			return;
		}
		running = false;
		RecordingStream s = stream;
		stream = null;
		if (s != null) {
			s.close();
		}
		// 清空采样数据，避免停止后 getSnapshot() 返回过期快照
		methodCounts.clear();
		threadTotal.clear();
	}

	public boolean isRunning() {
		return running;
	}

	private void onSample(RecordedEvent event) {
		if (!running) {
			return;
		}
		try {
			RecordedThread thread = event.getThread();
			if (thread == null) {
				return;
			}
			String threadName = thread.getJavaName();
			RecordedStackTrace stackTrace = event.getStackTrace();
			if (stackTrace == null) {
				return;
			}
			List<RecordedFrame> frames = stackTrace.getFrames();
			if (frames.isEmpty()) {
				return;
			}
			ConcurrentMap<String, Long> counts = methodCounts.computeIfAbsent(threadName, k -> new ConcurrentHashMap<>());
			for (RecordedFrame frame : frames) {
				RecordedMethod method = frame.getMethod();
				String key = method.getType().getName() + "." + method.getName();
				counts.merge(key, 1L, Long::sum);
			}
			threadTotal.merge(threadName, (long) frames.size(), Long::sum);
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
}
