package com.taskmanager.model;

/**
 * 线程信息：Java 线程名、线程 ID、原生线程 ID（nid）与资源占用。
 */
public final class ThreadInfo {
	private final String threadName;
	private final long threadId;
	private final long nativeId;
	private final ResourceUsage usage;

	public ThreadInfo(String threadName, long threadId, long nativeId, ResourceUsage usage) {
		this.threadName = threadName;
		this.threadId = threadId;
		this.nativeId = nativeId;
		this.usage = usage;
	}

	public String threadName() {
		return threadName;
	}

	public long threadId() {
		return threadId;
	}

	/** 操作系统原生线程 ID（nid），-1 表示未知。 */
	public long nativeId() {
		return nativeId;
	}

	public ResourceUsage usage() {
		return usage;
	}

	@Override
	public String toString() {
		return "ThreadInfo{name=" + threadName + ", id=" + threadId + ", nid=" + nativeId + "}";
	}
}
