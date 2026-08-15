package com.taskmanager.sampling;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingStream;

/**
 * 线程 nid（OS 原生线程 ID）注册表。
 * <p>
 * Java 无纯公开 API 跨线程获取 OS 原生线程 ID，唯一可靠的 JDK 内置方式是 JFR：
 * <ul>
 *   <li>{@code jdk.ThreadStart}：捕获流启动后新建线程的 OSThreadId（事件驱动，即时）；</li>
 *   <li>{@code jdk.ThreadEnd}：清理已退出线程的映射，防泄漏；</li>
 *   <li>{@code jdk.ThreadDump}（3s 周期）：其 {@code result} 文本为 jstack 风格，
 *       每行 {@code "name" #javaId [osId] ... nid=osId}，解析补齐流启动前已存在线程的 nid
 *       （{@code jdk.ExecutionSample} 只采样执行中线程、{@code ThreadStart} 不补发存量线程，均无法覆盖空闲老线程）。</li>
 * </ul>
 * 拿不到的线程返回 -1（展示 N/A）。JFR 非侵入、开销低；本注册表随采样器生命周期启停。
 */
public final class NidRegistry {
	/** 匹配 jstack 线程行：`"线程名" #Java线程ID [OS线程ID] ...`。 */
	private static final Pattern THREAD_LINE = Pattern.compile("\"([^\"]*)\"\\s*#(\\d+)\\s*\\[(\\d+)\\]");

	private final ConcurrentMap<Long, Long> javaToOs = new ConcurrentHashMap<>();
	private volatile RecordingStream stream;
	private volatile boolean running;

	/** 启动采集（幂等）。失败（JFR 不可用）时静默降级，nidOf 恒返回 -1。 */
	public synchronized void start() {
		if (running) {
			return;
		}
		RecordingStream s = new RecordingStream();
		try {
			s.enable("jdk.ThreadStart");
			s.enable("jdk.ThreadEnd");
			s.enable("jdk.ThreadDump").withPeriod(Duration.ofSeconds(3));
			s.onEvent("jdk.ThreadStart", this::recordThread);
			s.onEvent("jdk.ThreadEnd", this::forgetThread);
			s.onEvent("jdk.ThreadDump", this::onThreadDump);
			s.startAsync();
			stream = s;
			running = true;
		} catch (RuntimeException | Error e) {
			// JFR 不可用：安全降级，不崩溃
			s.close();
		}
	}

	/** 停止采集并清空映射。 */
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
		javaToOs.clear();
	}

	private void recordThread(RecordedEvent event) {
		try {
			RecordedThread thread = event.getThread();
			if (thread != null) {
				javaToOs.put(thread.getJavaThreadId(), thread.getOSThreadId());
			}
		} catch (RuntimeException ignored) {
		}
	}

	private void forgetThread(RecordedEvent event) {
		try {
			RecordedThread thread = event.getThread();
			if (thread != null) {
				javaToOs.remove(thread.getJavaThreadId());
			}
		} catch (RuntimeException ignored) {
		}
	}

	/** 解析 ThreadDump 文本，补齐存量线程的 Java线程ID→nid 映射。 */
	private void onThreadDump(RecordedEvent event) {
		try {
			String result = event.getString("result");
			if (result == null || result.isEmpty()) {
				return;
			}
			Matcher m = THREAD_LINE.matcher(result);
			while (m.find()) {
				try {
					javaToOs.put(Long.parseLong(m.group(2)), Long.parseLong(m.group(3)));
				} catch (NumberFormatException ignored) {
				}
			}
		} catch (RuntimeException ignored) {
		}
	}

	/** 查询 Java 线程 ID 对应的 OS 原生线程 ID（nid），未知返回 -1。 */
	public long nidOf(long javaThreadId) {
		Long nid = javaToOs.get(javaThreadId);
		return nid == null ? -1L : nid;
	}
}
