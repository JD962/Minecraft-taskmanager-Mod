package com.taskmanager.core;

import com.taskmanager.api.ProcessAdapter;
import com.taskmanager.api.ProcessState;
import com.taskmanager.model.Process;
import com.taskmanager.model.ProcessCategory;
import com.taskmanager.model.ProcessSide;
import com.taskmanager.model.ProcessSource;
import com.taskmanager.registry.ProcessAdapterRegistry;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;

/**
 * 进程管理器：PID 分配、进程登记、实体进程随实体增删动态创建/销毁、全局进程登记。
 * <p>
 * 实体进程以实体 UUID（全局唯一）为索引键，避免实体运行时 ID 跨维度冲突/复用导致误删；
 * 注册/注销/销毁共用一把注册表锁，保证进程表与实体索引的跨 Map 一致性。
 */
public final class ProcessManager {
	private static final ProcessManager INSTANCE = new ProcessManager();

	/** PID 从 1 开始分配，运行期稳定且不回收复用。 */
	private final AtomicInteger pidCounter = new AtomicInteger(1);
	/** 进程表（PID → 进程）。 */
	private final Map<Integer, Process> processes = new ConcurrentHashMap<>();
	/** 实体索引（实体 UUID → 进程）。 */
	private final Map<UUID, Process> entityProcesses = new ConcurrentHashMap<>();
	/** 注册表锁：保证进程表与实体索引的跨 Map 原子一致性。 */
	private final Object registryLock = new Object();

	/** 当前服务器实例（用于实体副作用调度到主线程），服务器停止时清空。 */
	private volatile MinecraftServer server;

	private ProcessManager() {
	}

	public static ProcessManager getInstance() {
		return INSTANCE;
	}

	/** 设置当前服务器实例（服务器启动时调用）。 */
	public void setServer(MinecraftServer server) {
		this.server = server;
	}

	/** 当前服务器实例，可能为 null（纯客户端）。 */
	public MinecraftServer server() {
		return server;
	}

	/** 注册实体进程（实体加载时调用）；已存在则返回已有进程。 */
	public Process registerEntity(Entity entity) {
		Objects.requireNonNull(entity, "entity");
		UUID uuid = entity.getUUID();
		synchronized (registryLock) {
			Process existing = entityProcesses.get(uuid);
			if (existing != null) {
				return existing;
			}
			String name = entity.getName().getString();
			Process process = new Process(nextPid(), name, ProcessSource.game(), ProcessCategory.ENTITY,
				ProcessSide.SERVER, classifyEntity(entity), entity, entity.getId(), uuid);
			bindAdapter(process, ProcessSource.GAME_ID, name);
			processes.put(process.pid(), process);
			entityProcesses.put(uuid, process);
			return process;
		}
	}

	/** 注销实体进程（实体卸载时调用）。 */
	public Process unregisterEntity(Entity entity) {
		Objects.requireNonNull(entity, "entity");
		UUID uuid = entity.getUUID();
		synchronized (registryLock) {
			Process process = entityProcesses.get(uuid);
			if (process == null) {
				return null;
			}
			// 恢复被暂停的 AI，避免 noAI 状态随实体持久化
			if (process.state() == ProcessState.PAUSED && entity instanceof Mob mob) {
				mob.setNoAi(false);
			}
			process.setState(ProcessState.TERMINATED);
			entityProcesses.remove(uuid, process);
			processes.remove(process.pid(), process);
			return process;
		}
	}

	/** 注册全局进程（世界 tick、渲染循环、网络 IO 等系统级任务）。 */
	public Process registerGlobal(String name, ProcessSource source, ProcessSide side) {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(side, "side");
		Process process = new Process(nextPid(), name, source, ProcessCategory.GLOBAL, side, null, null, -1, null);
		bindAdapter(process, source.id(), name);
		processes.put(process.pid(), process);
		return process;
	}

	/** 销毁指定进程节点（条件删除，避免误删复用键）。 */
	public void destroy(int pid) {
		synchronized (registryLock) {
			Process process = processes.get(pid);
			if (process == null) {
				return;
			}
			processes.remove(pid, process);
			if (process.entityUuid() != null) {
				entityProcesses.remove(process.entityUuid(), process);
			}
			process.setState(ProcessState.TERMINATED);
		}
	}

	public Process get(int pid) {
		return processes.get(pid);
	}

	public Process getByEntity(Entity entity) {
		return entity != null ? entityProcesses.get(entity.getUUID()) : null;
	}

	public Collection<Process> all() {
		return List.copyOf(processes.values());
	}

	public List<Process> bySource(String sourceId) {
		return processes.values().stream()
			.filter(p -> Objects.equals(p.source().id(), sourceId))
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
		String lower = keyword.trim().toLowerCase(java.util.Locale.ROOT);
		return processes.values().stream()
			.filter(p -> p.name().toLowerCase(java.util.Locale.ROOT).contains(lower)
				|| String.valueOf(p.pid()).equals(keyword.trim()))
			.toList();
	}

	public int size() {
		return processes.size();
	}

	/** 清空全部进程（服务器停止时调用），先恢复暂停实体 AI 再清表。 */
	public void clear() {
		synchronized (registryLock) {
			for (Process process : processes.values()) {
				Object target = process.target();
				if (process.state() == ProcessState.PAUSED && target instanceof Mob mob) {
					mob.setNoAi(false);
				}
				process.setState(ProcessState.TERMINATED);
			}
			processes.clear();
			entityProcesses.clear();
		}
	}

	private void bindAdapter(Process process, String sourceId, String name) {
		ProcessAdapter adapter = ProcessAdapterRegistry.getInstance().find(sourceId, name);
		if (adapter != null) {
			process.setAdapter(adapter);
		}
	}

	private int nextPid() {
		int pid = pidCounter.getAndIncrement();
		if (pid <= 0) {
			throw new IllegalStateException("PID 空间耗尽");
		}
		return pid;
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
