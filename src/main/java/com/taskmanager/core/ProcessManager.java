package com.taskmanager.core;

import com.taskmanager.api.ProcessAdapter;
import com.taskmanager.api.ProcessState;
import com.taskmanager.model.Process;
import com.taskmanager.model.ProcessCategory;
import com.taskmanager.model.ProcessSource;
import com.taskmanager.registry.ProcessAdapterRegistry;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;

/**
 * 进程管理器：PID 分配、进程树构建、实体进程随实体增删动态创建/销毁、全局进程登记。
 */
public final class ProcessManager {
	private static final ProcessManager INSTANCE = new ProcessManager();

	/** PID 从 1 开始分配，运行期稳定且不回收复用。 */
	private final AtomicInteger pidCounter = new AtomicInteger(1);
	private final Map<Integer, Process> processes = new ConcurrentHashMap<>();
	private final Map<Integer, Process> entityProcesses = new ConcurrentHashMap<>();

	private ProcessManager() {
	}

	public static ProcessManager getInstance() {
		return INSTANCE;
	}

	/** 注册实体进程（实体加载时调用）；已存在则返回已有进程。 */
	public Process registerEntity(Entity entity) {
		int entityId = entity.getId();
		Process existing = entityProcesses.get(entityId);
		if (existing != null) {
			return existing;
		}
		String name = entity.getName().getString();
		String subCategory = classifyEntity(entity);
		Process process = new Process(nextPid(), name, ProcessSource.game(), ProcessCategory.ENTITY,
			subCategory, entity, entityId);
		bindAdapter(process, ProcessSource.GAME_ID, name);
		processes.put(process.pid(), process);
		entityProcesses.put(entityId, process);
		return process;
	}

	/** 注销实体进程（实体卸载时调用）。 */
	public Process unregisterEntity(Entity entity) {
		int entityId = entity.getId();
		Process process = entityProcesses.remove(entityId);
		if (process != null) {
			// 恢复被暂停的 AI，避免 noAI 状态随实体持久化
			if (process.state() == ProcessState.PAUSED && entity instanceof Mob mob) {
				mob.setNoAi(false);
			}
			process.setState(ProcessState.TERMINATED);
			processes.remove(process.pid());
		}
		return process;
	}

	/** 注册全局进程（世界 tick、渲染循环、网络 IO 等系统级任务）。 */
	public Process registerGlobal(String name, ProcessSource source) {
		Process process = new Process(nextPid(), name, source, ProcessCategory.GLOBAL, null, null, -1);
		bindAdapter(process, source.id(), name);
		processes.put(process.pid(), process);
		return process;
	}

	/** 销毁指定进程节点。 */
	public void destroy(int pid) {
		Process process = processes.remove(pid);
		if (process != null && process.entityId() >= 0) {
			entityProcesses.remove(process.entityId());
		}
	}

	public Process get(int pid) {
		return processes.get(pid);
	}

	public Process getByEntityId(int entityId) {
		return entityProcesses.get(entityId);
	}

	public Collection<Process> all() {
		return List.copyOf(processes.values());
	}

	public List<Process> bySource(String sourceId) {
		return processes.values().stream()
			.filter(p -> p.source().id().equals(sourceId))
			.toList();
	}

	public List<Process> byState(ProcessState state) {
		return processes.values().stream()
			.filter(p -> p.state() == state)
			.toList();
	}

	/** 按名称或 PID 搜索（名称包含匹配、PID 精确匹配）。 */
	public List<Process> search(String keyword) {
		if (keyword == null || keyword.isBlank()) {
			return List.copyOf(processes.values());
		}
		String lower = keyword.trim().toLowerCase();
		return processes.values().stream()
			.filter(p -> {
				if (p.name().toLowerCase().contains(lower)) {
					return true;
				}
				return String.valueOf(p.pid()).equals(keyword.trim());
			})
			.toList();
	}

	public int size() {
		return processes.size();
	}

	/** 清空全部进程（服务器停止时调用）。 */
	public void clear() {
		processes.clear();
		entityProcesses.clear();
	}

	private void bindAdapter(Process process, String sourceId, String name) {
		ProcessAdapter adapter = ProcessAdapterRegistry.getInstance().find(sourceId, name);
		if (adapter != null) {
			process.setAdapter(adapter);
		}
	}

	private int nextPid() {
		return pidCounter.getAndIncrement();
	}

	/** 实体细分类别：玩家 / 掉落物实体 / 生物 / 其他实体。 */
	private static String classifyEntity(Entity entity) {
		if (entity instanceof Player) {
			return "玩家";
		}
		if (entity instanceof ItemEntity) {
			return "掉落物实体";
		}
		if (entity instanceof LivingEntity) {
			return "生物";
		}
		return "其他实体";
	}
}
