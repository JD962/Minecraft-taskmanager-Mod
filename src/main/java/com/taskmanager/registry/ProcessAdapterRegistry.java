package com.taskmanager.registry;

import com.taskmanager.api.ProcessAdapter;
import com.taskmanager.model.Process;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程适配器注册表。模组/游戏在此注册自定义适配逻辑。
 * <p>
 * 匹配规则：优先精确匹配「来源ID:进程名」，其次通配「来源ID:*」（该来源全部进程）。
 */
public final class ProcessAdapterRegistry {
	private static final ProcessAdapterRegistry INSTANCE = new ProcessAdapterRegistry();

	private static final String WILDCARD = "*";

	private final Map<String, ProcessAdapter> adapters = new ConcurrentHashMap<>();

	private ProcessAdapterRegistry() {
	}

	public static ProcessAdapterRegistry getInstance() {
		return INSTANCE;
	}

	/** 为某来源的特定名称进程注册适配器。 */
	public void register(String sourceId, String processName, ProcessAdapter adapter) {
		adapters.put(key(sourceId, processName), adapter);
	}

	/** 为某来源的全部进程注册适配器。 */
	public void register(String sourceId, ProcessAdapter adapter) {
		adapters.put(key(sourceId, WILDCARD), adapter);
	}

	/** 为指定进程绑定适配器（覆盖注册表匹配）。 */
	public void bind(Process process, ProcessAdapter adapter) {
		process.setAdapter(adapter);
	}

	/** 按来源与进程名查找适配器；找不到返回 null。 */
	public ProcessAdapter find(String sourceId, String processName) {
		ProcessAdapter adapter = adapters.get(key(sourceId, processName));
		if (adapter == null) {
			adapter = adapters.get(key(sourceId, WILDCARD));
		}
		return adapter;
	}

	/** 解除某来源特定名称进程的适配器注册。 */
	public void unregister(String sourceId, String processName) {
		adapters.remove(key(sourceId, processName));
	}

	private static String key(String sourceId, String processName) {
		return sourceId + ":" + processName;
	}
}
