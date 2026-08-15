package com.taskmanager.core;

import com.taskmanager.api.ProcessAdapter;
import com.taskmanager.api.ProcessState;
import com.taskmanager.debug.DebugLogger;
import com.taskmanager.model.Process;
import com.taskmanager.model.ProcessCategory;
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

	/** 暂停：运行中 → 已暂停（逻辑暂停：标志位，真实线程不挂起）。 */
	public boolean pause(Process process, String operator) {
		Objects.requireNonNull(process, "process");
		if (process.state() != ProcessState.RUNNING) {
			log(operator, "暂停", process, "失败: 状态非运行中");
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

	/** 恢复：已暂停 → 运行中。 */
	public boolean resume(Process process, String operator) {
		Objects.requireNonNull(process, "process");
		if (process.state() != ProcessState.PAUSED) {
			log(operator, "恢复", process, "失败: 状态非已暂停");
			return false;
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

	/** 终止：已终止则幂等成功；逻辑终止（全局进程仅改状态标记）。 */
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

	/** 强制终止：内置底层路径，不可被覆盖；已终止则幂等成功。 */
	public boolean forceTerminate(Process process, String operator) {
		Objects.requireNonNull(process, "process");
		if (process.state() == ProcessState.TERMINATED) {
			log(operator, "强制终止", process, "忽略: 已终止");
			return true;
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
		if (process.target() instanceof Entity) {
			log(operator, "重启", process, "失败: 实体进程需适配器支持重启");
			return false;
		}
		try {
			process.setState(ProcessState.RUNNING);
			log(operator, "重启", process, "成功");
			return true;
		} catch (Exception e) {
			log(operator, "重启", process, "失败: " + e.getMessage());
			return false;
		}
	}

	/** 调整优先级：1~5 档，3 为默认（逻辑优先级，仅对真实线程映射时生效）。 */
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
			process.setPriority(level);
			log(operator, "调整优先级", process, "成功(" + level + ")");
			return true;
		} catch (Exception e) {
			log(operator, "调整优先级", process, "失败: " + e.getMessage());
			return false;
		}
	}

	/** 启动：待启动 → 运行中；已暂停 → 恢复；已终止的实体进程不能原地启动。 */
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
		if (state == ProcessState.TERMINATED && process.target() instanceof Entity) {
			log(operator, "启动", process, "失败: 已销毁实体不能原地启动");
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

	/** 实体进程暂停/恢复：Mob 通过 noAI 冻结/恢复 AI，其他实体仅做逻辑标记。 */
	private void applyEntityPause(Process process, boolean paused) {
		Object target = process.target();
		if (target instanceof Mob mob) {
			runOnServerThread(() -> mob.setNoAi(paused));
		}
	}

	/** 终止实体进程：移除对应实体（触发实体卸载事件以清理进程）。 */
	private void applyTerminate(Process process) {
		Object target = process.target();
		if (target instanceof Entity entity) {
			runOnServerThread(entity::discard);
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
		// 全局进程无专属真实线程可挂起/终止，操作是逻辑标记，明确标注避免误导
		String tag = process.category() == ProcessCategory.GLOBAL ? "（逻辑）" : "";
		String target = process.name() + tag + " [PID " + process.pid() + "]";
		OperationLog entry = new OperationLog(System.currentTimeMillis(), operator, action, target, result);
		synchronized (logLock) {
			if (logs.size() == MAX_LOGS) {
				logs.removeFirst();
			}
			logs.addLast(entry);
		}
		// 调试模式：同步到调试日志（落盘）
		DebugLogger.getInstance().recordOperation(operator, action, target, result);
	}
}
