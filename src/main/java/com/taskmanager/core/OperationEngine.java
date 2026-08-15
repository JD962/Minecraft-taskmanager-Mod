package com.taskmanager.core;

import com.taskmanager.api.ProcessAdapter;
import com.taskmanager.api.ProcessState;
import com.taskmanager.model.Process;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

/**
 * 操作引擎：暂停/恢复/终止/强制终止/重启/调整优先级/启动。
 * <p>
 * 操作遵循状态机校验，优先走适配器自定义逻辑，失败时回退默认行为；每次操作均记录操作日志。
 */
public final class OperationEngine {
	private static final OperationEngine INSTANCE = new OperationEngine();

	/** 操作日志上限，超出后移除最旧条目，防止内存无限增长。 */
	private static final int MAX_LOGS = 2000;

	private final List<OperationLog> logs = new CopyOnWriteArrayList<>();

	private OperationEngine() {
	}

	public static OperationEngine getInstance() {
		return INSTANCE;
	}

	/** 暂停：运行中 → 已暂停。 */
	public boolean pause(Process process, String operator) {
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

	/** 终止：受保护进程（不可终止）拒绝。 */
	public boolean terminate(Process process, String operator) {
		ProcessAdapter adapter = process.adapter();
		if (adapter != null && !adapter.isTerminable()) {
			log(operator, "终止", process, "失败: 进程受保护不可终止");
			return false;
		}
		applyTerminate(process);
		process.setState(ProcessState.TERMINATED);
		log(operator, "终止", process, "成功");
		return true;
	}

	/** 强制终止：内置底层路径，不可被覆盖。 */
	public boolean forceTerminate(Process process, String operator) {
		applyTerminate(process);
		process.clearThreads();
		process.setState(ProcessState.TERMINATED);
		log(operator, "强制终止", process, "成功");
		return true;
	}

	/** 重启：先终止再启动；有自定义适配则走自定义流程。 */
	public boolean restart(Process process, String operator) {
		try {
			ProcessAdapter adapter = process.adapter();
			if (adapter != null) {
				adapter.onRestart(process);
			} else {
				process.setState(ProcessState.TERMINATED);
				process.setState(ProcessState.RUNNING);
			}
			log(operator, "重启", process, "成功");
			return true;
		} catch (Exception e) {
			log(operator, "重启", process, "失败: " + e.getMessage());
			return false;
		}
	}

	/** 调整优先级：1~5 档，3 为默认。 */
	public boolean setPriority(Process process, int level, String operator) {
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

	/** 启动：待启动/已终止 → 运行中。 */
	public boolean start(Process process, String operator) {
		ProcessState state = process.state();
		if (state == ProcessState.RUNNING) {
			log(operator, "启动", process, "忽略: 已在运行");
			return true;
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
		return List.copyOf(logs);
	}

	/** 实体进程暂停/恢复：Mob 通过 noAI 冻结/恢复 AI，其他实体仅做逻辑标记。 */
	private void applyEntityPause(Process process, boolean paused) {
		Object target = process.target();
		if (target instanceof Mob mob) {
			mob.setNoAi(paused);
		}
	}

	/** 终止实体进程：移除对应实体（触发实体卸载事件以清理进程）。 */
	private void applyTerminate(Process process) {
		Object target = process.target();
		if (target instanceof Entity entity) {
			entity.discard();
		}
	}

	private void log(String operator, String action, Process process, String result) {
		String target = process.name() + " [PID " + process.pid() + "]";
		logs.add(new OperationLog(System.currentTimeMillis(), operator, action, target, result));
		if (logs.size() > MAX_LOGS) {
			logs.remove(0);
		}
	}
}
