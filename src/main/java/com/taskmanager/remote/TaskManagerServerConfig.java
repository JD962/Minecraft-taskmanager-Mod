package com.taskmanager.remote;

/**
 * 服务端配置。
 */
public record TaskManagerServerConfig(
	String bindHost, int port, String token, int authTimeoutSeconds, int maxLineLength, int blockingThreads
) {
	public static final int DEFAULT_PORT = 25566;

	public TaskManagerServerConfig {
		if (bindHost == null || bindHost.isBlank()) {
			throw new IllegalArgumentException("bindHost must not be blank");
		}
		if (port < 1 || port > 65535) {
			throw new IllegalArgumentException("port out of range: " + port);
		}
		if (token == null || token.isBlank()) {
			throw new IllegalArgumentException("token must not be blank");
		}
		if (authTimeoutSeconds < 1) {
			throw new IllegalArgumentException("authTimeoutSeconds must be >= 1");
		}
		if (maxLineLength < 1024) {
			throw new IllegalArgumentException("maxLineLength must be >= 1024");
		}
		if (blockingThreads < 1) {
			throw new IllegalArgumentException("blockingThreads must be >= 1");
		}
	}

	/** 本机绑定 + 默认端口的常用配置。 */
	public static TaskManagerServerConfig localhost(String token) {
		return new TaskManagerServerConfig("127.0.0.1", DEFAULT_PORT, token, 5, 1 << 20, 4);
	}
}
