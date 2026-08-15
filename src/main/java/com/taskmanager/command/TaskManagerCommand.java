package com.taskmanager.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.taskmanager.api.ProcessState;
import com.taskmanager.core.OperationEngine;
import com.taskmanager.core.ProcessManager;
import com.taskmanager.debug.DebugLogger;
import com.taskmanager.debug.PrcExporter;
import com.taskmanager.model.Process;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;

/**
 * /taskmgr 命令：与 UI 等价的进程操作接口。
 */
public final class TaskManagerCommand {
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
