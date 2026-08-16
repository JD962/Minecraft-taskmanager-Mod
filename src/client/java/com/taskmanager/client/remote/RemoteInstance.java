package com.taskmanager.client.remote;

import com.taskmanager.remote.OperationResult;
import com.taskmanager.remote.OverviewInfo;
import com.taskmanager.remote.ProcessAction;
import com.taskmanager.remote.ProcessInfo;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 一个远程 TaskManager 服务端实例（host:port + token），
 * 封装 {@link RemoteClient}，维护最近一次拉取的进程表快照（渲染循环非阻塞读取）。
 */
public final class RemoteInstance {
	private final String name;
	private final String host;
	private final int port;
	private final String token;
	private final RemoteClient client;

	private volatile List<ProcessInfo> snapshot = List.of();
	private volatile String status = "未连接";
	private volatile boolean refreshing;
	private volatile long lastRefreshTime;

	public RemoteInstance(String name, String host, int port, String token) {
		this.name = name == null || name.isBlank() ? host + ":" + port : name;
		this.host = host;
		this.port = port;
		this.token = token == null ? "" : token;
		this.client = new RemoteClient(host, port, this.token);
	}

	public String name() {
		return name;
	}

	public String host() {
		return host;
	}

	public int port() {
		return port;
	}

	public String token() {
		return token;
	}

	public String address() {
		return host + ":" + port;
	}

	public RemoteClient client() {
		return client;
	}

	/** 最近一次拉取的进程表快照（可能为空列表）。 */
	public List<ProcessInfo> snapshot() {
		return snapshot;
	}

	/** 最近一次拉取的远程概览指标（未连接时为空）。 */
	public OverviewInfo overview() {
		return client.lastOverview();
	}

	/** 连接状态文本（用于 UI 显示）。 */
	public String status() {
		return status;
	}

	/** 建立连接并认证。 */
	public boolean connect() {
		boolean ok = client.connect();
		status = ok ? "已连接" : "连接失败";
		return ok;
	}

	/** 异步拉取进程表，更新快照；未连接、已在拉取或距上次不足 1s 时跳过。 */
	public void refresh() {
		long now = System.currentTimeMillis();
		if (refreshing || !client.isConnected() || now - lastRefreshTime < 1000) {
			return;
		}
		lastRefreshTime = now;
		refreshing = true;
		client.list().whenComplete((list, err) -> {
			refreshing = false;
			if (err != null) {
				status = "拉取失败";
			} else {
				snapshot = list == null ? List.of() : list;
				status = "已连接";
			}
		});
	}

	/** 远程操作（暂停/恢复/终止/强制终止/重启/启动）。 */
	public CompletableFuture<OperationResult> operate(ProcessAction action, long pid, String operator) {
		return client.operate(action, pid, operator);
	}

	/** 断开并清空快照。 */
	public void disconnect() {
		client.disconnect();
		snapshot = List.of();
		status = "未连接";
	}
}
