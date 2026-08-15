package com.taskmanager.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.taskmanager.api.ProcessState;
import com.taskmanager.core.OperationEngine;
import com.taskmanager.core.ProcessManager;
import com.taskmanager.model.Process;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * /taskmgr 命令：与 UI 等价的进程操作接口。
 */
public final class TaskManagerCommand {
	private TaskManagerCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("taskmgr")
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
