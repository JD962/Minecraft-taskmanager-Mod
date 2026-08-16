package com.taskmanager.client.remote;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 远程实例管理器：维护远程 TaskManager 服务端实例列表与当前选中项。
 * <p>
 * 选中索引 {@code -1} 表示「本地集成」（当前 JVM 的 ProcessManager）；
 * 其余为远程实例（{@link RemoteInstance}）。
 * 渲染循环通过 {@link #snapshot()} 非阻塞读取选中实例的进程表快照，
 * 后台通过 {@link #refreshSelected()} 触发异步拉取。
 */
public final class RemoteInstances {
	private static final RemoteInstances INSTANCE = new RemoteInstances();

	/** 本地集成实例的固定索引。 */
	public static final int LOCAL_INDEX = -1;

	private final List<RemoteInstance> instances = new CopyOnWriteArrayList<>();
	private volatile int selectedIndex = LOCAL_INDEX;

	private RemoteInstances() {
	}

	public static RemoteInstances getInstance() {
		return INSTANCE;
	}

	/** 全部远程实例（只读）。 */
	public List<RemoteInstance> all() {
		return instances;
	}

	/** 当前选中索引；{@link #LOCAL_INDEX} 表示本地集成。 */
	public int selectedIndex() {
		return selectedIndex;
	}

	/** 当前选中的远程实例；本地时为 null。 */
	public RemoteInstance selected() {
		int i = selectedIndex;
		return i >= 0 && i < instances.size() ? instances.get(i) : null;
	}

	/** 是否选中本地集成实例。 */
	public boolean isLocal() {
		return selectedIndex == LOCAL_INDEX;
	}

	/** 切换选中实例（LOCAL_INDEX 切回本地）。 */
	public void select(int index) {
		if (index == LOCAL_INDEX || (index >= 0 && index < instances.size())) {
			selectedIndex = index;
		}
	}

	/** 添加远程实例并返回；连接失败仍保留（用户可手动重试/删除）。 */
	public RemoteInstance add(String name, String host, int port, String token) {
		RemoteInstance inst = new RemoteInstance(name, host, port, token);
		instances.add(inst);
		persist();
		return inst;
	}

	/** 按索引删除远程实例（断开连接）。 */
	public void remove(int index) {
		if (index >= 0 && index < instances.size()) {
			RemoteInstance inst = instances.remove(index);
			inst.disconnect();
			if (selectedIndex == index) {
				selectedIndex = LOCAL_INDEX;
			} else if (selectedIndex > index) {
				selectedIndex--;
			}
			persist();
		}
	}

	/** 从本地配置加载已保存的实例（客户端启动时调用），并异步自动连接（不阻塞 UI）。 */
	public void loadSaved() {
		java.util.List<RemoteInstance> loaded = new java.util.ArrayList<>();
		for (ClientInstancesStore.Entry e : ClientInstancesStore.load()) {
			loaded.add(new RemoteInstance(e.name(), e.host(), e.port(), e.token()));
		}
		instances.addAll(loaded);
		if (!loaded.isEmpty()) {
			Thread t = new Thread(() -> {
				for (RemoteInstance inst : loaded) {
					inst.connect();
					inst.refresh();
				}
			}, "tm-autoconnect");
			t.setDaemon(true);
			t.start();
		}
	}

	/** 持久化当前实例列表到本地配置。 */
	private void persist() {
		ClientInstancesStore.save(instances);
	}

	/** 清除全部远程实例（客户端退出时调用）。 */
	public void clear() {
		for (RemoteInstance inst : instances) {
			inst.disconnect();
		}
		instances.clear();
		selectedIndex = LOCAL_INDEX;
	}

	/** 刷新选中实例的进程表快照（本地实例无需刷新，远程实例异步拉取）。 */
	public void refreshSelected() {
		RemoteInstance inst = selected();
		if (inst != null) {
			inst.refresh();
		}
	}

	/** 选中实例的进程表快照；本地时返回空列表（本地走 ProcessManager）。 */
	public List<com.taskmanager.remote.ProcessInfo> snapshot() {
		RemoteInstance inst = selected();
		return inst != null ? inst.snapshot() : List.of();
	}
}
