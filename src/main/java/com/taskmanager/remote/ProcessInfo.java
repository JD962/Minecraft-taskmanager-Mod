package com.taskmanager.remote;

/** 单个进程的快照数据。 */
public record ProcessInfo(long pid, String name, String source, String category, String subCategory, String side, String state, double cpu, long memory) {
	public ProcessInfo {
		if (name == null) name = "";
		if (source == null) source = "";
		if (category == null) category = "";
		if (subCategory == null) subCategory = "";
		if (side == null) side = "";
		if (state == null) state = "unknown";
	}
}
