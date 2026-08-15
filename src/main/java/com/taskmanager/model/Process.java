package com.taskmanager.model;

import com.taskmanager.api.ProcessAdapter;
import com.taskmanager.api.ProcessState;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 进程节点（逻辑容器）。
 * <p>
 * 实体类进程对应游戏内实体（无专属 JVM 线程，线程列表可空或共享）；全局类进程对应系统级任务。
 */
public final class Process {
	private final int pid;
	private final ProcessSource source;
	private final ProcessCategory category;
	private final ProcessSide side;
	/** 实体细分类别（如「生物」「玩家」「掉落物实体」「其他实体」），全局类为 null */
	private final String subCategory;
	private final WeakReference<Object> targetRef;
	/** 关联实体 ID（实体进程），全局进程为 -1。实体 ID 为维度内编号，仅作展示，不作全局索引。 */
	private final int entityId;
	/** 关联实体 UUID（实体进程，全局唯一索引键），全局进程为 null。 */
	private final UUID entityUuid;
	private final long createTime;

	private volatile String name;
	private volatile ProcessState state;
	private volatile ProcessAdapter adapter;
	private volatile int priority = ProcessAdapter.DEFAULT_PRIORITY;
	private volatile ResourceUsage usage = ResourceUsage.EMPTY;
	private final List<ThreadInfo> threads = new CopyOnWriteArrayList<>();

	public Process(int pid, String name, ProcessSource source, ProcessCategory category, ProcessSide side, String subCategory,
	               Object target, int entityId, UUID entityUuid) {
		this.pid = pid;
		this.name = name;
		this.source = source;
		this.category = category;
		this.side = side;
		this.subCategory = subCategory;
		this.targetRef = target != null ? new WeakReference<>(target) : null;
		this.entityId = entityId;
		this.entityUuid = entityUuid;
		this.state = ProcessState.RUNNING;
		this.createTime = System.currentTimeMillis();
	}

	public int pid() {
		return pid;
	}

	public String name() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public ProcessSource source() {
		return source;
	}

	public ProcessCategory category() {
		return category;
	}

	public ProcessSide side() {
		return side;
	}

	public String subCategory() {
		return subCategory;
	}

	public ProcessState state() {
		return state;
	}

	public void setState(ProcessState state) {
		this.state = state;
	}

	public ProcessAdapter adapter() {
		return adapter;
	}

	public void setAdapter(ProcessAdapter adapter) {
		this.adapter = adapter;
	}

	public int priority() {
		return priority;
	}

	public void setPriority(int priority) {
		this.priority = priority;
	}

	/** 关联的目标对象（实体等），可能已被 GC 回收。 */
	public Object target() {
		return targetRef != null ? targetRef.get() : null;
	}

	public int entityId() {
		return entityId;
	}

	/** 关联实体 UUID（全局唯一索引键），全局进程为 null。 */
	public UUID entityUuid() {
		return entityUuid;
	}

	public long createTime() {
		return createTime;
	}

	public ResourceUsage usage() {
		return usage;
	}

	public void setUsage(ResourceUsage usage) {
		this.usage = usage;
	}

	public List<ThreadInfo> threads() {
		return threads;
	}

	public void addThread(ThreadInfo thread) {
		threads.add(thread);
	}

	public void removeThread(ThreadInfo thread) {
		threads.remove(thread);
	}

	public void clearThreads() {
		threads.clear();
	}

	@Override
	public String toString() {
		return "Process{pid=" + pid + ", name=" + name + ", source=" + source + ", category=" + category
			+ ", state=" + state + "}";
	}
}
