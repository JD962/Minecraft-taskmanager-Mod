package com.taskmanager.debug;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 调试日志器：调试模式开关、模组行为日志（操作/事件流）、线程创建/销毁追踪、日志文件导出。
 * <p>
 * 打开调试模式后，才记录并导出日志（命名 {@code 日志_YYYY-MM-DD_HH-MM-SS.log}），
 * 与 {@link PrcExporter} 导出的 {@code .prc} 文件配套，便于追溯同一时刻系统状态。
 */
public final class DebugLogger {
	private static final DebugLogger INSTANCE = new DebugLogger();

	/** 内存日志环形缓冲上限，防止无限增长。 */
	private static final int MAX_BUFFERED = 500;
	/** 每累计多少条日志 flush 一次（降低系统调用/磁盘同步压力）。 */
	private static final int FLUSH_THRESHOLD = 50;

	private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

	private final Object bufferLock = new Object();
	private final Object writeLock = new Object();
	private final Object threadDiffLock = new Object();

	/** 环形缓冲：锁保护，O(1) 添加/删除。 */
	private final ArrayDeque<String> buffered = new ArrayDeque<>(MAX_BUFFERED);

	private volatile boolean debugEnabled = false;
	private volatile Path logFile;
	private volatile BufferedWriter writer;
	private int pendingLines = 0;

	/** 上一次线程集合（用于追踪线程创建/销毁）。 */
	private final Set<Long> lastThreadIds = new HashSet<>();

	private DebugLogger() {
	}

	public static DebugLogger getInstance() {
		return INSTANCE;
	}

	public boolean isEnabled() {
		return debugEnabled;
	}

	/** 打开调试模式：创建带时间戳的日志文件，开始记录。 */
	public synchronized boolean enable() {
		if (debugEnabled) {
			return true;
		}
		try {
			Path dir = PrcExporter.defaultDirectory();
			Path file = dir.resolve(PrcExporter.timestampedName("日志", ".log"));
			BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			synchronized (writeLock) {
				logFile = file;
				writer = w;
				pendingLines = 0;
				debugEnabled = true;
			}
			record("调试模式已开启，日志文件: " + file.getFileName());
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	/** 关闭调试模式：先记录关闭消息，再关闭 writer，并清空线程基线。 */
	public synchronized boolean disable() {
		if (!debugEnabled) {
			return false;
		}
		record("调试模式已关闭");

		boolean ok = true;
		synchronized (writeLock) {
			debugEnabled = false;
			BufferedWriter w = writer;
			writer = null;
			pendingLines = 0;
			if (w != null) {
				try {
					w.close();
				} catch (IOException e) {
					ok = false;
				}
			}
			logFile = null;
		}
		synchronized (threadDiffLock) {
			lastThreadIds.clear();
		}
		return ok;
	}

	public Path logFile() {
		return logFile;
	}

	/** 记录一条调试日志（事件流/操作/状态）。未开启调试模式时静默忽略。 */
	public void record(String message) {
		if (!debugEnabled) {
			return;
		}
		String line = "[" + LocalDateTime.now().format(TS) + "] " + message;

		synchronized (bufferLock) {
			if (buffered.size() == MAX_BUFFERED) {
				buffered.removeFirst();
			}
			buffered.addLast(line);
		}

		boolean shouldFlush = false;
		synchronized (writeLock) {
			BufferedWriter w = writer;
			if (w == null) {
				return;
			}
			try {
				w.write(line);
				w.newLine();
				pendingLines++;
				if (pendingLines >= FLUSH_THRESHOLD) {
					w.flush();
					pendingLines = 0;
				}
			} catch (IOException e) {
				// 写入失败：摘除并关闭损坏的 writer，关闭调试模式，避免持续无效写入
				writer = null;
				debugEnabled = false;
				try {
					w.close();
				} catch (IOException closeError) {
					e.addSuppressed(closeError);
				}
			}
		}
	}

	/** 记录一条操作日志（与 OperationEngine 的日志同步，调试模式下落盘）。 */
	public void recordOperation(String operator, String action, String target, String result) {
		record("操作 [" + action + "] 操作者=" + operator + " 目标=" + target + " 结果=" + result);
	}

	/** 追踪线程创建/销毁：对比当前 JVM 线程集合与上次，记录差异事件。由采样线程在调试模式下调用。 */
	public void trackThreadDiff(Map<Long, String> threads) {
		if (!debugEnabled || threads == null) {
			return;
		}

		List<String> events = new ArrayList<>();
		synchronized (threadDiffLock) {
			for (Map.Entry<Long, String> e : threads.entrySet()) {
				if (!lastThreadIds.contains(e.getKey())) {
					events.add("线程创建: [" + e.getValue() + "] id=" + e.getKey());
				}
			}
			for (long id : lastThreadIds) {
				if (!threads.containsKey(id)) {
					events.add("线程销毁: id=" + id);
				}
			}
			lastThreadIds.clear();
			lastThreadIds.addAll(threads.keySet());
		}
		for (String event : events) {
			record(event);
		}
	}

	/** 最近缓冲的调试日志（供 UI 展示）。 */
	public List<String> buffered() {
		synchronized (bufferLock) {
			return new ArrayList<>(buffered);
		}
	}

	/** 清空内存缓冲（不关闭文件）。 */
	public void clearBuffered() {
		synchronized (bufferLock) {
			buffered.clear();
		}
	}
}
