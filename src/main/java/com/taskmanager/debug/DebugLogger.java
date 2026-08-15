package com.taskmanager.debug;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

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

	private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

	private final List<String> buffered = new CopyOnWriteArrayList<>();

	private volatile boolean debugEnabled = false;
	private volatile Path logFile;
	private volatile BufferedWriter writer;
	private final Object writeLock = new Object();

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
				java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
			logFile = file;
			writer = w;
			debugEnabled = true;
			record("调试模式已开启，日志文件: " + file.getFileName());
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	/** 关闭调试模式：刷新并关闭日志文件。 */
	public synchronized boolean disable() {
		if (!debugEnabled) {
			return false;
		}
		debugEnabled = false;
		record("调试模式已关闭");
		synchronized (writeLock) {
			if (writer != null) {
				try {
					writer.flush();
					writer.close();
				} catch (IOException ignored) {
				}
				writer = null;
			}
		}
		logFile = null;
		return true;
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
		buffered.add(line);
		if (buffered.size() > MAX_BUFFERED) {
			buffered.remove(0);
		}
		synchronized (writeLock) {
			if (writer != null) {
				try {
					writer.write(line);
					writer.newLine();
					writer.flush();
				} catch (IOException ignored) {
				}
			}
		}
	}

	/** 记录一条操作日志（与 OperationEngine 的日志同步，调试模式下落盘）。 */
	public void recordOperation(String operator, String action, String target, String result) {
		record("操作 [" + action + "] 操作者=" + operator + " 目标=" + target + " 结果=" + result);
	}

	/**
	 * 追踪线程创建/销毁：对比当前 JVM 线程集合与上次，记录差异事件。
	 * 由采样线程在调试模式下调用，复用其已获取的线程集合。
	 */
	public void trackThreadDiff(Set<Thread> threads) {
		if (!debugEnabled) {
			return;
		}
		Map<Long, Thread> current = new HashMap<>();
		for (Thread t : threads) {
			current.put(t.threadId(), t);
		}
		// 新增线程
		for (Map.Entry<Long, Thread> e : current.entrySet()) {
			if (!lastThreadIds.contains(e.getKey())) {
				record("线程创建: [" + e.getValue().getName() + "] id=" + e.getKey());
			}
		}
		// 销毁线程
		for (long id : lastThreadIds) {
			if (!current.containsKey(id)) {
				record("线程销毁: id=" + id);
			}
		}
		lastThreadIds.clear();
		lastThreadIds.addAll(current.keySet());
	}

	/** 最近缓冲的调试日志（供 UI 展示）。 */
	public List<String> buffered() {
		return new ArrayList<>(buffered);
	}

	/** 清空内存缓冲（不关闭文件）。 */
	public void clearBuffered() {
		buffered.clear();
	}
}
