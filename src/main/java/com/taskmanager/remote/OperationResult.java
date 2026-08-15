package com.taskmanager.remote;

/** 操作结果。 */
public record OperationResult(boolean success, String message) {
	public OperationResult {
		if (message == null) message = "";
	}

	public static OperationResult ok() {
		return new OperationResult(true, "ok");
	}

	public static OperationResult ok(String message) {
		return new OperationResult(true, message);
	}

	public static OperationResult fail(String message) {
		return new OperationResult(false, message);
	}
}
