package com.taskmanager.model;

/**
 * 线程信息快照：Java 线程名、Java 线程 ID、OS 原生线程 ID（nid）、状态、守护标记、优先级、
 * CPU 占用、累计分配字节数与当前栈顶帧。
 * <p>
 * 注意：Java 无「线程级存活内存」概念，故不提供线程内存字段；可用的是累计分配字节数
 * （{@link com.sun.management.ThreadMXBean#getThreadAllocatedBytes}）与由此计算的分配速率。
 * nid 通过 JFR（{@code jdk.ThreadStart}/{@code jdk.ExecutionSample} 的 OSThreadId）获取，
 * 无法获取时为 -1（展示 N/A）。
 */
public final class ThreadInfo {
	private final String threadName;
	private final long threadId;
	/** OS 原生线程 ID（nid），-1 表示未知。 */
	private final long nativeId;
	private final Thread.State state;
	private final boolean daemon;
	private final int priority;
	/** 累计分配字节数，-1 表示该 JVM 不支持线程分配监控。 */
	private final long allocatedBytes;
	/** 当前栈顶帧（"类名.方法名"），null 表示无堆栈。 */
	private final String topFrame;
	private final ResourceUsage usage;

	public ThreadInfo(String threadName, long threadId, long nativeId, Thread.State state, boolean daemon,
	                  int priority, long allocatedBytes, String topFrame, ResourceUsage usage) {
		this.threadName = threadName;
		this.threadId = threadId;
		this.nativeId = nativeId;
		this.state = state;
		this.daemon = daemon;
		this.priority = priority;
		this.allocatedBytes = allocatedBytes;
		this.topFrame = topFrame;
		this.usage = usage;
	}

	public String threadName() {
		return threadName;
	}

	public long threadId() {
		return threadId;
	}

	/** OS 原生线程 ID（nid），-1 表示未知。 */
	public long nativeId() {
		return nativeId;
	}

	public Thread.State state() {
		return state;
	}

	public boolean daemon() {
		return daemon;
	}

	public int priority() {
		return priority;
	}

	/** 累计分配字节数，-1 表示不支持。 */
	public long allocatedBytes() {
		return allocatedBytes;
	}

	/** 当前栈顶帧，null 表示无堆栈。 */
	public String topFrame() {
		return topFrame;
	}

	public ResourceUsage usage() {
		return usage;
	}

	@Override
	public String toString() {
		return "ThreadInfo{name=" + threadName + ", id=" + threadId + ", nid=" + nativeId + ", state=" + state
			+ ", daemon=" + daemon + ", prio=" + priority + "}";
	}
}
