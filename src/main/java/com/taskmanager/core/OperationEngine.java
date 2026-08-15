package com.taskmanager.core;

import com.taskmanager.api.Freezable;
import com.taskmanager.api.ManagedTask;
import com.taskmanager.api.ProcessAdapter;
import com.taskmanager.api.ProcessState;
import com.taskmanager.debug.DebugLogger;
import com.taskmanager.model.Process;
import com.taskmanager.model.ProcessCategory;
import com.taskmanager.model.ThreadInfo;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

/**
 * 操作引擎：暂停/恢复/终止/强制终止/重启/调整优先级/启动。
 * <p>
 * 操作遵循状态机校验，优先走适配器自定义逻辑，失败时回退默认行为；每次操作均记录操作日志。
 * 注意：实体副作用（setNoAi/discard）必须在服务器主线程执行，调用方需保证线程正确性。
 */
public final class OperationEngine {
	private static final OperationEngine INSTANCE = new OperationEngine();

	/** 操作日志上限，超出后移除最旧条目，防止内存无限增长。 */
	private static final int MAX_LOGS = 2000;

	private final Object logLock = new Object();
	private final ArrayDeque<OperationLog> logs = new ArrayDeque<>(MAX_LOGS);

	private OperationEngine() {
	}

	public static OperationEngine getInstance() {
		return INSTANCE;
	}

	/** 暂停：运行中 → 已暂停。Freezable 真实冻结（失败回退逻辑标记）；实体 noAI；受管任务协作标志。 */
	public boolean pause(Process process, String operator) {
		Objects.requireNonNull(process, "process");
		if (process.state() != ProcessState.RUNNING) {
			log(operator, "暂停", process, "失败: 状态非运行中");
			return false;
		}
		Object target = process.target();
		if (target instanceof Freezable freezable) {
			// 真实冻结，失败回退到逻辑标记（兼容性与回退余量）
			return pauseFreezable(process, operator, freezable);
		}
		if (!(target instanceof Mob) && !(target instanceof ManagedTask)) {
			log(operator, "暂停", process, "失败: 系统核心线程不支持真实挂起（Thread.suspend 已废弃）");
			return false;
		}
		try {
			ProcessAdapter adapter = process.adapter();
			if (adapter != null) {
				adapter.onPause(process);
			}
			applyEntityPause(process, true);
			process.setState(ProcessState.PAUSED);
			log(operator, "暂停", process, "成功");
			return true;
		} catch (Exception e) {
			log(operator, "暂停", process, "失败: " + e.getMessage());
			return false;
		}
	}

	/** 暂停可冻结目标：真实冻结成功则记录真实副作用，失败则回退逻辑标记（不崩溃、不假装真实）。 */
	private boolean pauseFreezable(Process process, String operator, Freezable freezable) {
		try {
			ProcessAdapter adapter = process.adapter();
			if (adapter != null) {
				adapter.onPause(process);
			}
			boolean reallyFrozen = freezable.freeze();
			process.setState(ProcessState.PAUSED);
			log(operator, "暂停", process, reallyFrozen ? "成功（真实冻结服务器 tick）" : "成功（逻辑，真实冻结不可用）");
			return true;
		} catch (Exception e) {
			log(operator, "暂停", process, "失败: " + e.getMessage());
			return false;
		}
	}

	/** 恢复：已暂停 → 运行中。Freezable 真实解冻；实体/受管任务恢复。 */
	public boolean resume(Process process, String operator) {
		Objects.requireNonNull(process, "process");
		if (process.state() != ProcessState.PAUSED) {
			log(operator, "恢复", process, "失败: 状态非已暂停");
			return false;
		}
		Object target = process.target();
		if (target instanceof Freezable freezable) {
			return resumeFreezable(process, operator, freezable);
		}
		try {
			ProcessAdapter adapter = process.adapter();
			if (adapter != null) {
				adapter.onResume(process);
			}
			applyEntityPause(process, false);
			process.setState(ProcessState.RUNNING);
			log(operator, "恢复", process, "成功");
			return true;
		} catch (Exception e) {
			log(operator, "恢复", process, "失败: " + e.getMessage());
			return false;
		}
	}

	/** 恢复可冻结目标：真实解冻成功则记录，失败回退逻辑标记。 */
	private boolean resumeFreezable(Process process, String operator, Freezable freezable) {
		try {
			ProcessAdapter adapter = process.adapter();
			if (adapter != null) {
				adapter.onResume(process);
			}
			boolean reallyUnfrozen = freezable.unfreeze();
			process.setState(ProcessState.RUNNING);
			log(operator, "恢复", process, reallyUnfrozen ? "成功（真实解冻服务器 tick）" : "成功（逻辑，真实解冻不可用）");
			return true;
		} catch (Exception e) {
			log(operator, "恢复", process, "失败: " + e.getMessage());
			return false;
		}
	}

	/** 终止：已终止则幂等成功；仅对可真实终止的目标生效（实体 discard / 受管任务中断）。 */
	public boolean terminate(Process process, String operator) {
		Objects.requireNonNull(process, "process");
		if (process.state() == ProcessState.TERMINATED) {
			log(operator, "终止", process, "忽略: 已终止");
			return true;
		}
		ProcessAdapter adapter = process.adapter();
		if (adapter != null && !adapter.isTerminable()) {
			log(operator, "终止", process, "失败: 进程受保护不可终止");
			return false;
		}
		Object target = process.target();
		if (!(target instanceof Entity) && !(target instanceof ManagedTask)) {
			log(operator, "终止", process, "失败: 系统核心线程不支持真实终止");
			return false;
		}
		try {
			applyTerminate(process);
			process.setState(ProcessState.TERMINATED);
			log(operator, "终止", process, "成功");
			return true;
		} catch (Exception e) {
			log(operator, "终止", process, "失败: " + e.getMessage());
			return false;
		}
	}

	/** 强制终止：内置底层路径，不可被覆盖；已终止则幂等成功；系统核心线程同样拒绝。 */
	public boolean forceTerminate(Process process, String operator) {
		Objects.requireNonNull(process, "process");
		if (process.state() == ProcessState.TERMINATED) {
			log(operator, "强制终止", process, "忽略: 已终止");
			return true;
		}
		Object target = process.target();
		if (!(target instanceof Entity) && !(target instanceof ManagedTask)) {
			log(operator, "强制终止", process, "失败: 系统核心线程不支持真实终止");
			return false;
		}
		try {
			applyTerminate(process);
			process.clearThreads();
			process.setState(ProcessState.TERMINATED);
			log(operator, "强制终止", process, "成功");
			return true;
		} catch (Exception e) {
			log(operator, "强制终止", process, "失败: " + e.getMessage());
			return false;
		}
	}

	/** 重启：有适配器走自定义流程；无适配器的实体进程不支持原地重启。 */
	public boolean restart(Process process, String operator) {
		Objects.requireNonNull(process, "process");
		ProcessAdapter adapter = process.adapter();
		if (adapter != null) {
			try {
				adapter.onRestart(process);
				process.setState(ProcessState.RUNNING);
				log(operator, "重启", process, "成功");
				return true;
			} catch (Exception e) {
				log(operator, "重启", process, "失败: " + e.getMessage());
				return false;
			}
		}
		Object target = process.target();
		if (target instanceof ManagedTask task) {
			try {
				task.restart();
				process.setState(ProcessState.RUNNING);
				log(operator, "重启", process, "成功");
				return true;
			} catch (Exception e) {
				log(operator, "重启", process, "失败: " + e.getMessage());
				return false;
			}
		}
		if (target instanceof Entity) {
			log(operator, "重启", process, "失败: 实体进程需适配器支持重启");
			return false;
		}
		// target 为 null 或 Freezable（系统核心全局进程）：无法真实终止再重建，诚实拒绝
		log(operator, "重启", process, "失败: 系统核心线程不支持重启（无法真实终止）");
		return false;
	}

	/** 调整优先级：1~5 档，3 为默认。对所有进程真实映射其线程的 Java 优先级。 */
	public boolean setPriority(Process process, int level, String operator) {
		Objects.requireNonNull(process, "process");
		if (level < ProcessAdapter.MIN_PRIORITY || level > ProcessAdapter.MAX_PRIORITY) {
			log(operator, "调整优先级", process, "失败: 档位越界(" + level + ")");
			return false;
		}
		try {
			ProcessAdapter adapter = process.adapter();
			if (adapter != null) {
				adapter.onSetPriority(process, level);
			}
			Object target = process.target();
			if (target instanceof ManagedTask task) {
				task.setPriority(level);
			}
			int mapped = applyThreadPriority(process, level);
			process.setPriority(level);
			log(operator, "调整优先级", process, "成功(" + level + "，映射 " + mapped + " 线程)");
			return true;
		} catch (Exception e) {
			log(operator, "调整优先级", process, "失败: " + e.getMessage());
			return false;
		}
	}

	/** 将进程下所有线程的真实 Java 优先级映射到档位（3 档 = NORM_PRIORITY），返回映射的线程数。 */
	private static int applyThreadPriority(Process process, int level) {
		int mapped = switch (level) {
			case 1 -> Thread.MIN_PRIORITY;
			case 2 -> 3;
			case 4 -> 7;
			case 5 -> Thread.MAX_PRIORITY;
			default -> Thread.NORM_PRIORITY;
		};
		int count = 0;
		for (ThreadInfo info : process.threads()) {
			Thread thread = findThread(info.threadId());
			if (thread != null) {
				thread.setPriority(mapped);
				count++;
			}
		}
		return count;
	}

	/** 按线程 ID 查找真实 Thread 对象（操作频率低，遍历获取可接受）。 */
	private static Thread findThread(long threadId) {
		for (Thread t : Thread.getAllStackTraces().keySet()) {
			if (t.threadId() == threadId) {
				return t;
			}
		}
		return null;
	}

	/** 启动：待启动 → 运行中；已暂停 → 恢复；已终止的受管任务重建线程；实体/系统线程拒绝。 */
	public boolean start(Process process, String operator) {
		Objects.requireNonNull(process, "process");
		ProcessState state = process.state();
		if (state == ProcessState.RUNNING) {
			log(operator, "启动", process, "忽略: 已在运行");
			return true;
		}
		if (state == ProcessState.PAUSED) {
			return resume(process, operator);
		}
		Object target = process.target();
		if (state == ProcessState.TERMINATED) {
			if (target instanceof ManagedTask task) {
				try {
					task.restart();
					process.setState(ProcessState.RUNNING);
					log(operator, "启动", process, "成功（重建线程）");
					return true;
				} catch (Exception e) {
					log(operator, "启动", process, "失败: " + e.getMessage());
					return false;
				}
			}
			log(operator, "启动", process, "失败: 已终止进程不能原地启动（实体已销毁/系统线程无法重建）");
			return false;
		}
		try {
			ProcessAdapter adapter = process.adapter();
			if (adapter != null) {
				adapter.onStart(process);
			}
			process.setState(ProcessState.RUNNING);
			log(operator, "启动", process, "成功");
			return true;
		} catch (Exception e) {
			log(operator, "启动", process, "失败: " + e.getMessage());
			return false;
		}
	}

	public List<OperationLog> logs() {
		synchronized (logLock) {
			return List.copyOf(logs);
		}
	}

	/** 实体/任务进程暂停/恢复：Mob 通过 noAI 冻结/恢复 AI；受管任务通过协作式标志位。 */
	private void applyEntityPause(Process process, boolean paused) {
		Object target = process.target();
		if (target instanceof Mob mob) {
			runOnServerThread(() -> mob.setNoAi(paused));
		} else if (target instanceof ManagedTask task) {
			task.setPaused(paused);
		}
	}

	/** 终止实体/任务进程：移除对应实体或中断受管任务线程。 */
	private void applyTerminate(Process process) {
		Object target = process.target();
		if (target instanceof Entity entity) {
			runOnServerThread(entity::discard);
		} else if (target instanceof ManagedTask task) {
			task.terminate();
		}
	}

	/** 实体副作用（setNoAi/discard）必须在服务器主线程执行；非主线程时阻塞调度到主线程。 */
	private static void runOnServerThread(Runnable action) {
		MinecraftServer server = ProcessManager.getInstance().server();
		if (server != null && !server.isSameThread()) {
			try {
				server.executeBlocking(action);
			} catch (Exception e) {
				// 服务器已停止或调度失败：此时实体世界已关闭，副作用无意义，安全忽略
			}
		} else {
			action.run();
		}
	}

	private void log(String operator, String action, Process process, String result) {
		// 仅当全局进程无真实控制目标（无实体/受管任务/可冻结目标）时，操作是纯逻辑标记，明确标注避免误导
		Object target = process.target();
		boolean logicalOnly = process.category() == ProcessCategory.GLOBAL
			&& !(target instanceof Mob) && !(target instanceof ManagedTask) && !(target instanceof Freezable);
		String tag = logicalOnly ? "（逻辑）" : "";
		String targetDesc = process.name() + tag + " [PID " + process.pid() + "]";
		OperationLog entry = new OperationLog(System.currentTimeMillis(), operator, action, targetDesc, result);
		synchronized (logLock) {
			if (logs.size() == MAX_LOGS) {
				logs.removeFirst();
			}
			logs.addLast(entry);
		}
		// 调试模式：同步到调试日志（落盘）
		DebugLogger.getInstance().recordOperation(operator, action, targetDesc, result);
	}
}
