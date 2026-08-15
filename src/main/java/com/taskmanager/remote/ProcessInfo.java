package com.taskmanager.remote;

/** 单个进程的快照数据。 */
public record ProcessInfo(long pid, String name, String state, double cpu, long memory) {
	public ProcessInfo {
		if (name == null) name = "";
		if (state == null) state = "unknown";
	}
}
