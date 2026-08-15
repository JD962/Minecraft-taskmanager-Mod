package com.taskmanager.remote;

import java.util.List;

/**
 * 进程数据提供者，由 ProcessManager 实现并注入 TaskManagerServer。
 * 方法在专用阻塞线程池中调用，需保证线程安全。
 */
public interface ProcessDataProvider {
	List<ProcessInfo> listProcesses() throws Exception;

	OperationResult operate(ProcessAction action, long pid, String operator) throws Exception;
}
