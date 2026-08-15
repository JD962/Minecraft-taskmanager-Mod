package com.taskmanager.core;

/**
 * 操作日志条目：记录一次进程操作的时间、操作者、动作、目标与结果。
 */
public record OperationLog(long timestamp, String operator, String action, String target, String result) {
}
