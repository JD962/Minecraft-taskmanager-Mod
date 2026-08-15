package com.taskmanager.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.taskmanager.api.ProcessState;
import com.taskmanager.core.OperationEngine;
import com.taskmanager.core.ProcessManager;
import com.taskmanager.debug.DebugLogger;
import com.taskmanager.debug.PrcExporter;
import com.taskmanager.debug.TestTask;
import com.taskmanager.model.Process;
import com.taskmanager.model.ThreadInfo;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.TickRateManager;

/**
 * /taskmgr 命令：与 UI 等价的进程操作接口。
 */
public final class TaskManagerCommand {
	/** 测试任务注册表（PID → 测试任务），用于运行时验证操作的真实副作用。 */
	private static final Map<Integer, TestTask> TEST_TASKS = new ConcurrentHashMap<>();

	private TaskManagerCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("taskmgr")
			.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
			.then(Commands.literal("list").executes(TaskManagerCommand::list))
			.then(Commands.literal("search")
				.then(Commands.argument("keyword", StringArgumentType.word()).executes(TaskManagerCommand::search)))
			.then(Commands.literal("pause")
				.then(Commands.argument("pid", IntegerArgumentType.integer()).executes(TaskManagerCommand::pause)))
			.then(Commands.literal("resume")
				.then(Commands.argument("pid", IntegerArgumentType.integer()).executes(TaskManagerCommand::resume)))
			.then(Commands.literal("kill")
				.then(Commands.argument("pid", IntegerArgumentType.integer()).executes(TaskManagerCommand::terminate)))
			.then(Commands.literal("forcekill")
				.then(Commands.argument("pid", IntegerArgumentType.integer()).executes(TaskManagerCommand::forceTerminate)))
			.then(Commands.literal("restart")
				.then(Commands.argument("pid", IntegerArgumentType.integer()).executes(TaskManagerCommand::restart)))
			.then(Commands.literal("priority")
				.then(Commands.argument("pid", IntegerArgumentType.integer())
					.then(Commands.argument("level", IntegerArgumentType.integer(1, 5)).executes(TaskManagerCommand::priority))))
			.then(Commands.literal("start")
				.then(Commands.argument("pid", IntegerArgumentType.integer()).executes(TaskManagerCommand::start)))
			.then(Commands.literal("export")
				.executes(TaskManagerCommand::exportOnce)
				.then(Commands.literal("realtime").executes(TaskManagerCommand::exportRealtime))
				.then(Commands.literal("stop").executes(TaskManagerCommand::exportStop))
				.then(Commands.literal("verify").executes(TaskManagerCommand::exportVerify)))
			.then(Commands.literal("debug")
				.executes(TaskManagerCommand::debugOn)
				.then(Commands.literal("off").executes(TaskManagerCommand::debugOff)))
			.then(Commands.literal("test").executes(TaskManagerCommand::createTestTask))
			.then(Commands.literal("testinfo")
				.then(Commands.argument("pid", IntegerArgumentType.integer()).executes(TaskManagerCommand::testInfo)))
			.then(Commands.literal("tickinfo").executes(TaskManagerCommand::tickInfo))
			.then(Commands.literal("threads")
				.then(Commands.argument("pid", IntegerArgumentType.integer()).executes(TaskManagerCommand::listThreads)))
			.then(Commands.literal("tpause")
				.then(Commands.argument("tid", LongArgumentType.longArg()).executes(TaskManagerCommand::pauseThread)))
			.then(Commands.literal("tresume")
				.then(Commands.argument("tid", LongArgumentType.longArg()).executes(TaskManagerCommand::resumeThread)))
			.then(Commands.literal("tkill")
				.then(Commands.argument("tid", LongArgumentType.longArg()).executes(TaskManagerCommand::terminateThread)))
			.then(Commands.literal("tforcekill")
				.then(Commands.argument("tid", LongArgumentType.longArg()).executes(TaskManagerCommand::forceTerminateThread)))
			.then(Commands.literal("tpriority")
				.then(Commands.argument("tid", LongArgumentType.longArg())
					.then(Commands.argument("level", IntegerArgumentType.integer(1, 5)).executes(TaskManagerCommand::threadPriority))))
			.then(Commands.literal("ttest").executes(TaskManagerCommand::threadOpTest))
		);
	}

	private static int list(CommandContext<CommandSourceStack> ctx) {
		List<Process> processes = ProcessManager.getInstance().all().stream()
			.sorted((a, b) -> Integer.compare(a.pid(), b.pid()))
			.toList();
		if (processes.isEmpty()) {
			send(ctx, "当前无进程。");
			return 0;
		}
		for (Process p : processes) {
			send(ctx, String.format("PID %d | %s | %s | %s",
				p.pid(), p.name(), p.state(), p.source().displayName()));
		}
		return processes.size();
	}

	private static int search(CommandContext<CommandSourceStack> ctx) {
		String keyword = StringArgumentType.getString(ctx, "keyword");
		List<Process> hits = ProcessManager.getInstance().search(keyword);
		if (hits.isEmpty()) {
			send(ctx, "未找到匹配进程: " + keyword);
			return 0;
		}
		for (Process p : hits) {
			send(ctx, String.format("PID %d | %s | %s", p.pid(), p.name(), p.state()));
		}
		return hits.size();
	}

	private static int pause(CommandContext<CommandSourceStack> ctx) {
		return operate(ctx, "暂停", OperationEngine.getInstance()::pause);
	}

	private static int resume(CommandContext<CommandSourceStack> ctx) {
		return operate(ctx, "恢复", OperationEngine.getInstance()::resume);
	}

	private static int terminate(CommandContext<CommandSourceStack> ctx) {
		return operate(ctx, "终止", OperationEngine.getInstance()::terminate);
	}

	private static int forceTerminate(CommandContext<CommandSourceStack> ctx) {
		return operate(ctx, "强制终止", OperationEngine.getInstance()::forceTerminate);
	}

	private static int restart(CommandContext<CommandSourceStack> ctx) {
		return operate(ctx, "重启", OperationEngine.getInstance()::restart);
	}

	private static int start(CommandContext<CommandSourceStack> ctx) {
		return operate(ctx, "启动", OperationEngine.getInstance()::start);
	}

	private static int priority(CommandContext<CommandSourceStack> ctx) {
		int pid = IntegerArgumentType.getInteger(ctx, "pid");
		int level = IntegerArgumentType.getInteger(ctx, "level");
		Process process = ProcessManager.getInstance().get(pid);
		if (process == null) {
			send(ctx, "进程不存在: PID " + pid);
			return 0;
		}
		String operator = ctx.getSource().getTextName();
		boolean ok = OperationEngine.getInstance().setPriority(process, level, operator);
		send(ctx, ok ? "优先级已调整: PID " + pid + " -> " + level : "调整失败: PID " + pid);
		return ok ? 1 : 0;
	}

	private static int exportOnce(CommandContext<CommandSourceStack> ctx) {
		Path file = PrcExporter.defaultDirectory()
			.resolve(PrcExporter.timestampedName("进程表", ".prc"));
		boolean ok = PrcExporter.getInstance().exportOnce(file);
		send(ctx, ok ? "进程表已导出: " + file.getFileName() : "导出失败");
		return ok ? 1 : 0;
	}

	private static int exportRealtime(CommandContext<CommandSourceStack> ctx) {
		boolean ok = PrcExporter.getInstance().startRealtime(1000);
		if (ok) {
			send(ctx, "实时导出已启动: " + PrcExporter.getInstance().realtimeFile().getFileName());
		} else {
			send(ctx, "实时导出启动失败");
		}
		return ok ? 1 : 0;
	}

	private static int exportStop(CommandContext<CommandSourceStack> ctx) {
		boolean ok = PrcExporter.getInstance().stopRealtime();
		send(ctx, ok ? "实时导出已停止" : "未在实时导出");
		return ok ? 1 : 0;
	}

	private static int exportVerify(CommandContext<CommandSourceStack> ctx) {
		Path file = PrcExporter.latestPrcFile();
		if (file == null) {
			send(ctx, "无 .prc 文件可校验");
			return 0;
		}
		try {
			PrcExporter.ReadResult result = PrcExporter.getInstance().read(file);
			if (result == null) {
				send(ctx, "校验失败（文件损坏）: " + file.getFileName());
				return 0;
			}
			byte[] raw = PrcExporter.ungzip(result.payload());
			int count = PrcExporter.countProcesses(raw);
			send(ctx, "校验通过: " + file.getFileName() + "，含 " + count + " 个进程（来源槽位 " + result.sourceSlot() + "）");
			return 1;
		} catch (Exception e) {
			send(ctx, "校验异常: " + e.getMessage());
			return 0;
		}
	}

	private static int debugOn(CommandContext<CommandSourceStack> ctx) {
		boolean ok = DebugLogger.getInstance().enable();
		send(ctx, ok ? "调试模式已开启" : "调试模式开启失败");
		return ok ? 1 : 0;
	}

	private static int debugOff(CommandContext<CommandSourceStack> ctx) {
		boolean ok = DebugLogger.getInstance().disable();
		send(ctx, ok ? "调试模式已关闭" : "调试模式未开启");
		return ok ? 1 : 0;
	}

	/** 创建测试任务：一个真实后台线程（循环计数），注册为受管进程，用于验证操作真实性。 */
	private static int createTestTask(CommandContext<CommandSourceStack> ctx) {
		String name = "TaskManager-Test-" + (TEST_TASKS.size() + 1);
		TestTask task = new TestTask(name);
		task.start();
		Process process = ProcessManager.getInstance().registerTask(name, task);
		TEST_TASKS.put(process.pid(), task);
		send(ctx, "测试任务已创建: PID " + process.pid() + " | 线程 " + name + "（用 /taskmgr pause/resume/kill/priority + testinfo 验证）");
		return 1;
	}

	/** 查询测试任务真实状态：计数（暂停/恢复验证）、线程优先级（优先级调整验证）、线程状态（终止验证）。 */
	private static int testInfo(CommandContext<CommandSourceStack> ctx) {
		int pid = IntegerArgumentType.getInteger(ctx, "pid");
		TestTask task = TEST_TASKS.get(pid);
		if (task == null) {
			send(ctx, "测试任务不存在: PID " + pid);
			return 0;
		}
		send(ctx, String.format("PID %d | 计数 %d | %s | 线程优先级 %d | 线程状态 %s",
			pid, task.counter(), task.paused() ? "已暂停" : "运行中",
			task.threadPriority(), task.threadState()));
		return 1;
	}

	/** 查询服务器 tick 真实状态（速率/冻结），用于验证「暂停/恢复服务端主循环」是否真实生效。 */
	private static int tickInfo(CommandContext<CommandSourceStack> ctx) {
		MinecraftServer server = ProcessManager.getInstance().server();
		if (server == null) {
			send(ctx, "服务器不可用（纯客户端环境）");
			return 0;
		}
		TickRateManager manager = server.tickRateManager();
		send(ctx, String.format("tick 速率 %.1f | 冻结 %s | 步进剩余 %d",
			manager.tickrate(), manager.isFrozen() ? "是" : "否", manager.frozenTicksToRun()));
		return 1;
	}

	/** 列出某进程下的线程（含 Java 线程 ID 与 nid），用于线程级操作定位。 */
	private static int listThreads(CommandContext<CommandSourceStack> ctx) {
		int pid = IntegerArgumentType.getInteger(ctx, "pid");
		Process process = ProcessManager.getInstance().get(pid);
		if (process == null) {
			send(ctx, "进程不存在: PID " + pid);
			return 0;
		}
		List<ThreadInfo> threads = process.threads();
		if (threads.isEmpty()) {
			send(ctx, "PID " + pid + " 无线程");
			return 0;
		}
		for (ThreadInfo t : threads) {
			String nid = t.nativeId() >= 0 ? "0x" + Long.toHexString(t.nativeId()) : "N/A";
			send(ctx, String.format("线程 #%d (nid=%s) | %s | %s | 优先级 %d",
				t.threadId(), nid, t.threadName(), t.state(), t.priority()));
		}
		return threads.size();
	}

	private static int pauseThread(CommandContext<CommandSourceStack> ctx) {
		return operateThread(ctx, "暂停线程", OperationEngine.getInstance()::pauseThread);
	}

	private static int resumeThread(CommandContext<CommandSourceStack> ctx) {
		return operateThread(ctx, "恢复线程", OperationEngine.getInstance()::resumeThread);
	}

	private static int terminateThread(CommandContext<CommandSourceStack> ctx) {
		return operateThread(ctx, "终止线程", OperationEngine.getInstance()::terminateThread);
	}

	private static int forceTerminateThread(CommandContext<CommandSourceStack> ctx) {
		return operateThread(ctx, "强制终止线程", OperationEngine.getInstance()::forceTerminateThread);
	}

	private static int threadPriority(CommandContext<CommandSourceStack> ctx) {
		long tid = LongArgumentType.getLong(ctx, "tid");
		int level = IntegerArgumentType.getInteger(ctx, "level");
		String operator = ctx.getSource().getTextName();
		boolean ok = OperationEngine.getInstance().setThreadPriority(tid, level, operator);
		send(ctx, (ok ? "线程优先级已调整" : "调整失败") + ": 线程 #" + tid + " -> " + level);
		return ok ? 1 : 0;
	}

	@FunctionalInterface
	private interface ThreadOperation {
		boolean apply(long threadId, String operator);
	}

	private static int operateThread(CommandContext<CommandSourceStack> ctx, String action, ThreadOperation operation) {
		long tid = LongArgumentType.getLong(ctx, "tid");
		String operator = ctx.getSource().getTextName();
		boolean ok = operation.apply(tid, operator);
		send(ctx, (ok ? action + "成功" : action + "失败（非受管任务线程，无真实副作用）") + ": 线程 #" + tid);
		return ok ? 1 : 0;
	}

	/** 端到端验证线程级操作：内部创建测试任务并对其线程执行暂停/恢复/终止，打印真实状态变化。 */
	private static int threadOpTest(CommandContext<CommandSourceStack> ctx) {
		TestTask task = new TestTask("TaskManager-ThreadTest");
		task.start();
		long threadId = task.threadIds().stream().findFirst().orElse(-1L);
		ProcessManager.getInstance().registerTask(task.name(), task);
		String operator = ctx.getSource().getTextName();
		OperationEngine engine = OperationEngine.getInstance();
		try {
			Thread.sleep(250L);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		long c0 = task.counter();
		boolean paused = engine.pauseThread(threadId, operator);
		try {
			Thread.sleep(300L);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		long c1 = task.counter();
		boolean resumed = engine.resumeThread(threadId, operator);
		try {
			Thread.sleep(200L);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		long c2 = task.counter();
		boolean killed = engine.terminateThread(threadId, operator);
		try {
			Thread.sleep(300L);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		send(ctx, String.format("线程 #%d | 暂停 %s(计数 %d→%d) | 恢复 %s(→%d) | 终止 %s(状态 %s)",
			threadId, paused, c0, c1, resumed, c2, killed, task.threadState()));
		return 1;
	}

	@FunctionalInterface
	private interface Operation {
		boolean apply(Process process, String operator);
	}

	private static int operate(CommandContext<CommandSourceStack> ctx, String action, Operation operation) {
		int pid = IntegerArgumentType.getInteger(ctx, "pid");
		Process process = ProcessManager.getInstance().get(pid);
		if (process == null) {
			send(ctx, "进程不存在: PID " + pid);
			return 0;
		}
		String operator = ctx.getSource().getTextName();
		boolean ok = operation.apply(process, operator);
		send(ctx, (ok ? action + "成功" : action + "失败") + ": PID " + pid);
		return ok ? 1 : 0;
	}

	private static void send(CommandContext<CommandSourceStack> ctx, String message) {
		ctx.getSource().sendSuccess(() -> Component.literal(message), false);
	}
}
