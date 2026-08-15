package com.taskmanager.api;

/**
 * 进程节点的运行状态。
 */
public enum ProcessState {
	/** 运行中 */
	RUNNING,
	/** 已暂停（逻辑暂停，等价于移入待办区） */
	PAUSED,
	/** 已终止 */
	TERMINATED,
	/** 待启动（尚未进入可执行状态） */
	PENDING_START
}
