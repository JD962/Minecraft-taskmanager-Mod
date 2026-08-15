package com.taskmanager.client.gui;

import com.taskmanager.api.ProcessState;
import com.taskmanager.core.OperationEngine;
import com.taskmanager.core.OperationLog;
import com.taskmanager.core.ProcessManager;
import com.taskmanager.debug.DebugLogger;
import com.taskmanager.debug.PrcExporter;
import com.taskmanager.model.Process;
import com.taskmanager.model.ProcessCategory;
import com.taskmanager.model.ProcessSide;
import com.taskmanager.model.ThreadInfo;
import com.taskmanager.sampling.MethodProfiler;
import com.taskmanager.sampling.ResourceSampler;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 任务管理器主界面：独立自定义 UI（Blaze3D 抽象渲染）。
 * <p>
 * 支持：亮/暗主题切换、客户端/服务端/全部页签、搜索、进程树（展开线程/方法级）、
 * 热力色、操作按钮、操作日志、设置页。
 */
public class TaskManagerScreen extends Screen {
	private static final Logger LOGGER = LoggerFactory.getLogger("TaskManager");
	private static final int TAB_CLIENT = 0;
	private static final int TAB_SERVER = 1;
	private static final int TAB_ALL = 2;
	private static final String[] TABS = {"taskmanager.tab.client", "taskmanager.tab.server", "taskmanager.tab.all"};

	private boolean darkMode = true;
	private int activeTab = TAB_ALL;
	private EditBox searchBox;
	private int selectedPid = -1;
	private long selectedThreadId = -1;
	private final Set<Integer> expandedProcesses = new HashSet<>();
	private final Set<Long> expandedThreads = new HashSet<>();
	private final Set<String> expandedSources = new HashSet<>();
	private final Set<String> expandedCategories = new HashSet<>();
	private final Set<String> expandedSubCategories = new HashSet<>();
	private boolean treeInitialized = false;
	private int scrollOffset = 0;
	private boolean showSettings = false;
	/** 方法级采样快照缓存（1s 刷新一次，避免渲染循环每帧重建大 Map 导致堆压力）。 */
	private Map<String, List<MethodProfiler.MethodNode>> cachedMethodSnapshot = Map.of();
	private long lastSnapshotTime = 0;

	public TaskManagerScreen() {
		super(Component.translatable("taskmanager.title"));
	}

	@Override
	protected void init() {
		LOGGER.info("[TM] init size={}x{} activeScreenIsThis={}", this.width, this.height,
			Minecraft.getInstance().gui.screen() == this);
		this.searchBox = new EditBox(this.font, 20, 58, 240, 16, Component.literal("搜索"));
		this.searchBox.setValue("");
		this.addRenderableWidget(this.searchBox);
		// 打开 UI 时启动方法级采样（周期跟随刷新频率），关闭 UI 时停止
		MethodProfiler.getInstance().start(
			MethodProfiler.periodForInterval(ResourceSampler.getInstance().intervalMs()));
	}

	@Override
	public void removed() {
		// 关闭 UI 时停止方法级采样，不影响 /taskmgr 命令
		MethodProfiler.getInstance().stop();
		super.removed();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(0, 0, this.width, this.height, bg());
		graphics.fill(0, 0, this.width, 26, panel());
		tmCentered(graphics, tr("taskmanager.title"), this.width / 2, 8, text());
		renderThemeButton(graphics);
		renderCloseButton(graphics);
		renderTabs(graphics);
		renderOverview(graphics);
		renderProcessList(graphics);
		renderDetailPanel(graphics);
		renderLogs(graphics);
		if (showSettings) {
			renderSettings(graphics);
		}
		// 渲染 widget（EditBox 搜索框等），置于最上层
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	// ===== 主题 / 关闭 =====
	private void renderThemeButton(GuiGraphicsExtractor graphics) {
		int x = Math.max(0, this.width - 76);
		int y = 5;
		graphics.fill(x, y, x + 48, y + 16, button());
		tmCentered(graphics, tr(darkMode ? "taskmanager.theme.dark" : "taskmanager.theme.light"), x + 24, y + 4, text());
	}

	/** 右上角关闭按钮。 */
	private void renderCloseButton(GuiGraphicsExtractor graphics) {
		int x = Math.max(0, this.width - 24);
		int y = 5;
		graphics.fill(x, y, x + 20, y + 16, button());
		tmCentered(graphics, "X", x + 10, y + 4, text());
	}

	// ===== 页签 =====
	private void renderTabs(GuiGraphicsExtractor graphics) {
		int tabWidth = 72;
		int startX = 20;
		int y = 34;
		for (int i = 0; i < TABS.length; i++) {
			int x = startX + i * (tabWidth + 4);
			boolean active = i == activeTab;
			graphics.fill(x, y, x + tabWidth, y + 20, active ? accent() : button());
			tmCentered(graphics, tr(TABS[i]), x + tabWidth / 2, y + 6, active ? 0xFFFFFFFF : textMuted());
		}
	}

	// ===== 系统资源概览 =====
	private void renderOverview(GuiGraphicsExtractor graphics) {
		ResourceSampler sampler = ResourceSampler.getInstance();
		double cpu = sampler.processCpuLoad();
		double sysCpu = sampler.systemCpuLoad();
		long heap = sampler.heapUsed();
		double gpu = sampler.gpuUsage();
		String cpuStr = Double.isNaN(cpu) ? "N/A" : String.format("%.1f%%", cpu);
		String sysStr = Double.isNaN(sysCpu) ? "N/A" : String.format("%.1f%%", sysCpu);
		String memStr = formatBytes(heap);
		String gpuStr = Double.isNaN(gpu) ? "N/A" : String.format("%.1f%%", gpu);
		tmText(graphics, String.format("进程CPU %s | 系统CPU %s | 堆 %s | GPU %s", cpuStr, sysStr, memStr, gpuStr),
			260, 40, textMuted());
	}

	// ===== 进程树 =====
	private static final int ROW_H = 14;
	private static final int KIND_SOURCE = 0;
	private static final int KIND_CATEGORY = 1;
	private static final int KIND_SUBCATEGORY = 2;
	private static final int KIND_PROCESS = 3;
	private static final int KIND_THREAD = 4;
	private static final int KIND_METHOD = 5;

	/** 扁平化可视行：渲染与命中测试共用，保证点击不偏移。key 为展开键（稳定来源 ID），label 为显示文字。 */
	private record Row(int kind, Process process, ThreadInfo thread, MethodProfiler.MethodNode method,
	                   String key, String label, int depth, int y) {
	}

	/** 构建树形行布局：来源 → 类别 → 细分类（实体）→ 进程 → 线程 → 方法。 */
	private List<Row> buildRows() {
		List<Process> processes = filteredProcesses();
		// 首次构建时默认展开来源与类别，让用户直接看到进程层（键用稳定来源 ID）
		if (!treeInitialized) {
			treeInitialized = true;
			for (Process p : processes) {
				String sid = p.source().id();
				expandedSources.add(sid);
				expandedCategories.add(sid + "::" + (p.category() == ProcessCategory.ENTITY ? "实体类" : "全局类"));
			}
		}
		// 按来源 ID 分组（稳定，不随显示名/本地化变化），显示用 displayName
		Map<String, List<Process>> bySource = new LinkedHashMap<>();
		Map<String, String> sourceLabels = new HashMap<>();
		for (Process p : processes) {
			String sid = p.source().id();
			bySource.computeIfAbsent(sid, k -> new ArrayList<>()).add(p);
			sourceLabels.putIfAbsent(sid, p.source().displayName());
		}
		List<Row> rows = new ArrayList<>();
		int y = 0;
		for (Map.Entry<String, List<Process>> src : bySource.entrySet()) {
			String sourceId = src.getKey();
			rows.add(new Row(KIND_SOURCE, null, null, null, sourceId, sourceLabels.get(sourceId), 0, y));
			y += ROW_H;
			if (!expandedSources.contains(sourceId)) {
				continue;
			}
			Map<String, List<Process>> byCategory = new LinkedHashMap<>();
			for (Process p : src.getValue()) {
				byCategory.computeIfAbsent(p.category() == ProcessCategory.ENTITY ? "实体类" : "全局类",
					k -> new ArrayList<>()).add(p);
			}
			for (Map.Entry<String, List<Process>> cat : byCategory.entrySet()) {
				String catKey = sourceId + "::" + cat.getKey();
				rows.add(new Row(KIND_CATEGORY, null, null, null, catKey, cat.getKey(), 1, y));
				y += ROW_H;
				if (!expandedCategories.contains(catKey)) {
					continue;
				}
				if ("实体类".equals(cat.getKey())) {
					Map<String, List<Process>> bySub = new LinkedHashMap<>();
					for (Process p : cat.getValue()) {
						String sub = p.subCategory() == null ? "其他实体" : p.subCategory();
						bySub.computeIfAbsent(sub, k -> new ArrayList<>()).add(p);
					}
					for (Map.Entry<String, List<Process>> sub : bySub.entrySet()) {
						String subKey = catKey + "::" + sub.getKey();
						rows.add(new Row(KIND_SUBCATEGORY, null, null, null, subKey, sub.getKey(), 2, y));
						y += ROW_H;
						if (!expandedSubCategories.contains(subKey)) {
							continue;
						}
						for (Process p : sub.getValue()) {
							y = addProcessRows(rows, p, y);
						}
					}
				} else {
					for (Process p : cat.getValue()) {
						y = addProcessRows(rows, p, y);
					}
				}
			}
		}
		return rows;
	}

	private int addProcessRows(List<Row> rows, Process p, int y) {
		rows.add(new Row(KIND_PROCESS, p, null, null, null, null, 3, y));
		y += ROW_H;
		if (expandedProcesses.contains(p.pid())) {
			for (ThreadInfo t : p.threads()) {
				rows.add(new Row(KIND_THREAD, p, t, null, null, null, 4, y));
				y += ROW_H;
				if (expandedThreads.contains(t.threadId())) {
					List<MethodProfiler.MethodNode> methods = methodSnapshot().get(t.threadName());
					if (methods != null) {
						int limit = Math.min(6, methods.size());
						for (int j = 0; j < limit; j++) {
							rows.add(new Row(KIND_METHOD, p, t, methods.get(j), null, null, 5, y));
							y += ROW_H;
						}
					}
				}
			}
		}
		return y;
	}

	private int listTop() {
		return 98;
	}

	/** 底部面板顶部坐标（= 列表底界）。小高度下至少给列表留一行空间，避免视口为负。 */
	private int panelTop() {
		return Math.max(this.listTop() + ROW_H, this.height - 150);
	}

	private int listBottom() {
		return panelTop();
	}

	/** 将滚动偏移 clamp 到当前内容范围，防止展开/折叠后内容滚出视口导致列表空白。 */
	private void clampScrollToContent(int rowCount) {
		int viewportHeight = listBottom() - listTop();
		int maxScroll = Math.max(0, rowCount * ROW_H - viewportHeight);
		scrollOffset = Math.min(Math.max(scrollOffset, 0), maxScroll);
	}

	/** 方法级快照（带 1s 缓存，避免渲染循环每帧重建大 Map）。 */
	private Map<String, List<MethodProfiler.MethodNode>> methodSnapshot() {
		long now = System.currentTimeMillis();
		if (now - lastSnapshotTime > 1000) {
			cachedMethodSnapshot = ResourceSampler.getInstance().methodSnapshot();
			lastSnapshotTime = now;
		}
		return cachedMethodSnapshot;
	}

	private void renderProcessList(GuiGraphicsExtractor graphics) {
		int x = 20;
		int y = 82;
		tmText(graphics, tr("taskmanager.col.pid"), x, y, textMuted());
		tmText(graphics, tr("taskmanager.col.name"), x + 50, y, textMuted());
		tmText(graphics, tr("taskmanager.col.state"), x + 220, y, textMuted());
		tmText(graphics, tr("taskmanager.col.cpu"), x + 300, y, textMuted());
		tmText(graphics, tr("taskmanager.col.memory"), x + 380, y, textMuted());

		int top = listTop();
		int bottom = listBottom();
		List<Row> rows = buildRows();
		clampScrollToContent(rows.size());
		for (Row row : rows) {
			int screenY = top + row.y() - scrollOffset;
			if (screenY + ROW_H <= top || screenY >= bottom) {
				continue;
			}
			switch (row.kind()) {
				case KIND_SOURCE -> renderGroupRow(graphics, row.label(), 0, screenY,
					expandedSources.contains(row.key()), text());
				case KIND_CATEGORY -> renderGroupRow(graphics, row.label(), 1, screenY,
					expandedCategories.contains(row.key()), textMuted());
				case KIND_SUBCATEGORY -> renderGroupRow(graphics, row.label(), 2, screenY,
					expandedSubCategories.contains(row.key()), textMuted());
				case KIND_PROCESS -> renderProcessRow(graphics, row.process(), row.depth(), x, screenY);
				case KIND_THREAD -> renderThreadRow(graphics, row.thread(), row.depth(), x, screenY);
				default -> tmText(graphics, String.format("    %s %.1f%%",
					row.method().methodName(), row.method().cpuRatio()), x + 46 + row.depth() * 16, screenY, textMuted());
			}
		}
		if (rows.isEmpty()) {
			tmText(graphics, tr("taskmanager.empty"), x, top, textMuted());
		}
	}

	private void renderGroupRow(GuiGraphicsExtractor graphics, String label, int depth, int screenY,
	                            boolean expanded, int color) {
		int x = 20 + depth * 16;
		tmText(graphics, expanded ? "v" : ">", x, screenY, color);
		tmText(graphics, label, x + 12, screenY, color);
	}

	private void renderProcessRow(GuiGraphicsExtractor graphics, Process p, int depth, int x, int screenY) {
		int ind = depth * 16;
		if (p.pid() == selectedPid) {
			graphics.fill(15, screenY - 1, x + 440, screenY + 13, accent());
		}
		boolean expanded = expandedProcesses.contains(p.pid());
		tmText(graphics, expanded ? "v" : ">", x + ind, screenY, text());
		tmText(graphics, String.valueOf(p.pid()), x + ind + 12, screenY, text());
		tmText(graphics, p.name(), x + 50 + ind, screenY, text());
		tmText(graphics, tr(stateKey(p.state())), x + 220 + ind, screenY, stateColor(p));
		tmText(graphics, cpuText(p), x + 300 + ind, screenY, heatColor(p.usage().cpuUsage()));
		tmText(graphics, memoryText(p), x + 380 + ind, screenY, textMuted());
	}

	private void renderThreadRow(GuiGraphicsExtractor graphics, ThreadInfo thread, int depth, int x, int screenY) {
		int ind = depth * 16;
		if (thread.threadId() == selectedThreadId) {
			graphics.fill(15, screenY - 1, x + 440, screenY + 13, accent());
		}
		boolean expanded = expandedThreads.contains(thread.threadId());
		tmText(graphics, expanded ? "v" : ">", x + ind, screenY, textMuted());
		String prefix = thread.daemon() ? "*" : "-";
		tmText(graphics, prefix + " " + thread.threadName(), x + ind + 12, screenY, textMuted());
		tmText(graphics, stateText(thread.state()), x + 170 + ind, screenY, stateColor2(thread.state()));
		tmText(graphics, cpuText2(thread), x + 250 + ind, screenY, heatColor(thread.usage().cpuUsage()));
		tmText(graphics, allocText(thread.allocatedBytes()), x + 320 + ind, screenY, textMuted());
	}

	private static String cpuText2(ThreadInfo t) {
		double cpu = t.usage().cpuUsage();
		return Double.isNaN(cpu) ? "采样中" : String.format("%.1f%%", cpu);
	}

	private static String stateText(Thread.State state) {
		return switch (state) {
			case RUNNABLE -> "运行中";
			case BLOCKED -> "阻塞";
			case WAITING -> "等待";
			case TIMED_WAITING -> "限时等待";
			case NEW -> "新建";
			case TERMINATED -> "已结束";
		};
	}

	private static int stateColor2(Thread.State state) {
		return switch (state) {
			case RUNNABLE -> 0xFF55CC55;
			case BLOCKED -> 0xFFCC5555;
			case WAITING, TIMED_WAITING -> 0xFFDDAA22;
			default -> 0xFF888888;
		};
	}

	private static String allocText(long bytes) {
		if (bytes < 0) {
			return "";
		}
		if (bytes < 1024 * 1024) {
			return String.format("%.0f KB", bytes / 1024.0);
		}
		if (bytes < 1024L * 1024 * 1024) {
			return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
		}
		return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
	}

	// ===== 详情面板 + 操作按钮 =====
	private void renderDetailPanel(GuiGraphicsExtractor graphics) {
		Process selected = selectedPid < 0 ? null : ProcessManager.getInstance().get(selectedPid);
		ThreadInfo selectedThread = selectedThreadId < 0 ? null : findThread(selectedThreadId);
		int panelTop = panelTop();
		graphics.fill(0, panelTop, this.width, this.height, panel());
		if (selectedThread != null) {
			renderThreadDetail(graphics, selectedThread, panelTop);
			return;
		}
		if (selected == null) {
			tmText(graphics, tr("taskmanager.hint"), 20, panelTop + 8, textMuted());
			// 日志/设置按钮不依赖选中进程，始终渲染可用
			renderActionButton(graphics, tr("taskmanager.btn.logs"), 20, panelTop + 74);
			renderActionButton(graphics, tr("taskmanager.btn.settings"), 78, panelTop + 74);
			return;
		}
		boolean global = selected.category() == ProcessCategory.GLOBAL;
		String memDetail = selected.usage().heapMemory() >= 0
			? String.format("堆 %s / 非堆 %s", formatBytes(selected.usage().heapMemory()), formatBytes(selected.usage().nonHeapMemory()))
			: "-";
		tmText(graphics, String.format("PID %d %s | %s | %s %d | CPU %s | %s %s%s",
			selected.pid(), selected.name(), tr(stateKey(selected.state())),
			tr("taskmanager.priority"), selected.priority(), cpuText(selected),
			tr("taskmanager.col.memory"), memDetail,
			global ? " (JVM共享)" : ""), 20, panelTop + 8, text());
		renderActionButton(graphics, tr("taskmanager.btn.pause"), 20, panelTop + 26);
		renderActionButton(graphics, tr("taskmanager.btn.resume"), 78, panelTop + 26);
		renderActionButton(graphics, tr("taskmanager.btn.terminate"), 136, panelTop + 26);
		renderActionButton(graphics, tr("taskmanager.btn.force_terminate"), 194, panelTop + 26);
		renderActionButton(graphics, tr("taskmanager.btn.restart"), 274, panelTop + 26);
		renderActionButton(graphics, tr("taskmanager.btn.priority_down"), 20, panelTop + 50);
		renderActionButton(graphics, tr("taskmanager.btn.priority_up"), 88, panelTop + 50);
		renderActionButton(graphics, tr("taskmanager.btn.run_new_task"), 156, panelTop + 50, 84);
		renderActionButton(graphics, tr("taskmanager.btn.logs"), 20, panelTop + 74);
		renderActionButton(graphics, tr("taskmanager.btn.settings"), 78, panelTop + 74);
	}

	private void renderThreadDetail(GuiGraphicsExtractor graphics, ThreadInfo t, int panelTop) {
		String alloc = t.allocatedBytes() >= 0 ? " | 分配 " + allocText(t.allocatedBytes()) : "";
		tmText(graphics, String.format("线程 %s (#%d) | %s | %s | %s %d | CPU %s%s",
			t.threadName(), t.threadId(), stateText(t.state()),
			t.daemon() ? "守护" : "非守护", tr("taskmanager.priority"), t.priority(),
			cpuText2(t), alloc), 20, panelTop + 8, text());
		if (t.topFrame() != null) {
			tmText(graphics, "栈顶: " + t.topFrame(), 20, panelTop + 24, textMuted());
		}
		renderActionButton(graphics, tr("taskmanager.btn.priority_down"), 20, panelTop + 44);
		renderActionButton(graphics, tr("taskmanager.btn.priority_up"), 88, panelTop + 44);
		tmText(graphics, "线程暂停/终止不可用（JVM 无安全挂起 API）", 156, panelTop + 48, textMuted());
		renderActionButton(graphics, tr("taskmanager.btn.logs"), 20, panelTop + 74);
		renderActionButton(graphics, tr("taskmanager.btn.settings"), 78, panelTop + 74);
	}

	private ThreadInfo findThread(long threadId) {
		for (Process p : ProcessManager.getInstance().all()) {
			for (ThreadInfo t : p.threads()) {
				if (t.threadId() == threadId) {
					return t;
				}
			}
		}
		return null;
	}

	/** 调整线程优先级：仅允许非核心线程，核心线程（渲染/服务端/Netty/JFR/GC 等）不可调。 */
	private static void adjustThreadPriority(long threadId, int delta) {
		Thread thread = findJavaThread(threadId);
		if (thread == null || isCoreThread(thread.getName())) {
			return;
		}
		int newPrio = Math.clamp(thread.getPriority() + delta, Thread.MIN_PRIORITY, Thread.MAX_PRIORITY);
		thread.setPriority(newPrio);
	}

	/** 核心线程黑名单：调整这些线程优先级会导致卡顿/时序问题。 */
	private static boolean isCoreThread(String name) {
		return name.startsWith("Server thread") || name.startsWith("Render thread")
			|| name.startsWith("Netty") || name.contains("JFR") || name.startsWith("TaskManager")
			|| name.startsWith("Reference") || name.startsWith("Finalizer")
			|| name.startsWith("GC") || name.startsWith("Signal") || name.startsWith("Common-Cleaner");
	}

	private static Thread findJavaThread(long threadId) {
		for (Thread t : Thread.getAllStackTraces().keySet()) {
			if (t.threadId() == threadId) {
				return t;
			}
		}
		return null;
	}

	private void renderActionButton(GuiGraphicsExtractor graphics, String label, int x, int y) {
		renderActionButton(graphics, label, x, y, 56);
	}

	private void renderActionButton(GuiGraphicsExtractor graphics, String label, int x, int y, int w) {
		graphics.fill(x, y, x + w, y + 18, button());
		tmCentered(graphics, label, x + w / 2, y + 5, text());
	}

	private boolean showLogs = false;

	// ===== 操作日志 =====
	private void renderLogs(GuiGraphicsExtractor graphics) {
		if (!showLogs) {
			return;
		}
		int panelTop = panelTop();
		int logTop = this.height / 3;
		graphics.fill(20, logTop, this.width - 20, panelTop - 4, bg());
		tmText(graphics, tr("taskmanager.logs.title"), 24, logTop + 6, text());
		List<OperationLog> logs = OperationEngine.getInstance().logs();
		int y = logTop + 24;
		int count = 0;
		for (int i = logs.size() - 1; i >= 0 && count < 6; i--, count++, y += 12) {
			OperationLog log = logs.get(i);
			tmText(graphics, String.format("[%s] %s %s -> %s", time(log.timestamp()), log.action(), log.target(), log.result()), 24, y, textMuted());
		}
		// 调试模式：追加事件流（线程创建/销毁、导出状态等）
		if (DebugLogger.getInstance().isEnabled()) {
			List<String> debug = DebugLogger.getInstance().buffered();
			y += 6;
			for (int i = debug.size() - 1; i >= 0 && y < panelTop - 10; i--, y += 12) {
				tmText(graphics, debug.get(i), 24, y, textMuted());
			}
		}
	}

	private static String time(long millis) {
		java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("HH:mm:ss");
		return fmt.format(new java.util.Date(millis));
	}

	// ===== 设置 =====
	private void renderSettings(GuiGraphicsExtractor graphics) {
		int cx = this.width / 2 - 120;
		int cy = this.height / 2 - 100;
		graphics.fill(cx, cy, cx + 240, cy + 200, panel());
		tmCentered(graphics, tr("taskmanager.settings.title"), cx + 120, cy + 6, text());
		tmText(graphics, tr("taskmanager.settings.interval").formatted(ResourceSampler.getInstance().intervalMs()), cx + 10, cy + 28, text());
		renderActionButton(graphics, "0.5s", cx + 10, cy + 44);
		renderActionButton(graphics, "1s", cx + 70, cy + 44);
		renderActionButton(graphics, "5s", cx + 130, cy + 44);

		// 调试模式开关
		tmText(graphics, tr("taskmanager.settings.debug"), cx + 10, cy + 70, text());
		boolean debugOn = DebugLogger.getInstance().isEnabled();
		renderActionButton(graphics, tr(debugOn ? "taskmanager.settings.debug_off" : "taskmanager.settings.debug_on"), cx + 130, cy + 70, 100);

		// 进程表导出
		tmText(graphics, tr("taskmanager.settings.export"), cx + 10, cy + 96, text());
		renderActionButton(graphics, tr("taskmanager.settings.export_once"), cx + 10, cy + 118);
		renderActionButton(graphics, tr(PrcExporter.getInstance().isRealtimeRunning()
			? "taskmanager.settings.export_stop" : "taskmanager.settings.export_realtime"), cx + 70, cy + 118, 100);
		renderActionButton(graphics, tr("taskmanager.settings.export_verify"), cx + 174, cy + 118, 56);

		// 导出状态
		PrcExporter ex = PrcExporter.getInstance();
		tmText(graphics, "写 " + ex.writeCount() + " | " + ex.lastValidation(), cx + 10, cy + 144, textMuted());

		renderActionButton(graphics, tr("taskmanager.settings.close"), cx + 92, cy + 172);
	}

	// ===== 交互 =====
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x();
		double my = event.y();
		LOGGER.info("[TM] click ({}, {}) size={}x{} settings={} tab={} scroll={} btn={}",
			mx, my, this.width, this.height, showSettings, activeTab, scrollOffset, event.button());
		// 关闭按钮（右上角 X）
		if (mx >= this.width - 24 && mx < this.width - 4 && my >= 5 && my < 21) {
			this.onClose();
			return true;
		}
		// 设置面板（严格模态，最优先）：打开时只响应面板内点击，面板外关闭并吞事件
		if (showSettings) {
			int cx = this.width / 2 - 120;
			int cy = this.height / 2 - 100;
			if (hit(mx, my, cx + 10, cy + 44)) {
				ResourceSampler.getInstance().setInterval(500);
				return true;
			}
			if (hit(mx, my, cx + 70, cy + 44)) {
				ResourceSampler.getInstance().setInterval(1000);
				return true;
			}
			if (hit(mx, my, cx + 130, cy + 44)) {
				ResourceSampler.getInstance().setInterval(5000);
				return true;
			}
			if (hit(mx, my, cx + 130, cy + 70, 100)) {
				DebugLogger debug = DebugLogger.getInstance();
				if (debug.isEnabled()) {
					debug.disable();
				} else {
					debug.enable();
				}
				return true;
			}
			if (hit(mx, my, cx + 10, cy + 118)) {
				exportOnce();
				return true;
			}
			if (hit(mx, my, cx + 70, cy + 118, 100)) {
				toggleRealtime();
				return true;
			}
			if (hit(mx, my, cx + 174, cy + 118, 56)) {
				exportVerify();
				return true;
			}
			if (hit(mx, my, cx + 92, cy + 172)) {
				showSettings = false;
				return true;
			}
			// 面板外关闭并吞事件（左闭右开），面板内未命中按钮也吞事件
			if (mx < cx || mx >= cx + 240 || my < cy || my >= cy + 200) {
				showSettings = false;
			}
			return true;
		}
		// 主题按钮
		if (mx >= this.width - 76 && mx < this.width - 28 && my >= 5 && my < 21) {
			darkMode = !darkMode;
			return true;
		}
		// 页签
		for (int i = 0; i < TABS.length; i++) {
			int x = 20 + i * 76;
			if (mx >= x && mx < x + 72 && my >= 34 && my < 54) {
				activeTab = i;
				scrollOffset = 0;
				return true;
			}
		}
		// 进程/线程列表点击（选中/展开）——用与渲染一致的扁平化布局做命中测试，避免偏移
		List<Row> rows = buildRows();
		clampScrollToContent(rows.size());
		int top = listTop();
		int bottom = listBottom();
		// 鼠标必须在列表区域内（顶部页签以上、底部面板以下）
		if (my >= top && my < bottom) {
			for (Row row : rows) {
				int screenY = top + row.y() - scrollOffset;
				if (screenY >= bottom) {
					break; // 行按 y 递增，超出列表底界后不再匹配
				}
				int hitTop = Math.max(screenY, top);
				int hitBottom = Math.min(screenY + ROW_H, bottom);
				if (my >= hitTop && my < hitBottom) {
					switch (row.kind()) {
						case KIND_SOURCE -> {
							if (expandedSources.contains(row.key())) {
								expandedSources.remove(row.key());
							} else {
								expandedSources.add(row.key());
							}
							return true;
						}
						case KIND_CATEGORY -> {
							if (expandedCategories.contains(row.key())) {
								expandedCategories.remove(row.key());
							} else {
								expandedCategories.add(row.key());
							}
							return true;
						}
						case KIND_SUBCATEGORY -> {
							if (expandedSubCategories.contains(row.key())) {
								expandedSubCategories.remove(row.key());
							} else {
								expandedSubCategories.add(row.key());
							}
							return true;
						}
						case KIND_PROCESS -> {
							Process p = row.process();
							int arrowX = 20 + row.depth() * 16;
							if (mx >= arrowX && mx < arrowX + 14) {
								// 点击箭头：切换展开/折叠
								if (expandedProcesses.contains(p.pid())) {
									expandedProcesses.remove(p.pid());
								} else {
									expandedProcesses.add(p.pid());
								}
							} else {
								// 点击行其他区域：选中进程（详情面板显示 + 操作按钮）
								selectedThreadId = -1;
								selectedPid = p.pid();
							}
							return true;
						}
						case KIND_THREAD -> {
							ThreadInfo t = row.thread();
							int arrowX = 20 + row.depth() * 16;
							if (mx >= arrowX && mx < arrowX + 14) {
								// 点击箭头：切换方法级展开/折叠
								if (expandedThreads.contains(t.threadId())) {
									expandedThreads.remove(t.threadId());
								} else {
									expandedThreads.add(t.threadId());
								}
							} else {
								// 点击行其他区域：选中线程（详情面板显示线程信息 + 线程操作）
								selectedThreadId = t.threadId();
								selectedPid = -1;
							}
							return true;
						}
						default -> {
							// 方法行不可点击
						}
					}
				}
			}
		}
		// 日志/设置按钮（不依赖选中进程，始终可用）
		{
			int panelTop = panelTop();
			if (hit(mx, my, 20, panelTop + 74)) {
				showLogs = !showLogs;
				return true;
			}
			if (hit(mx, my, 78, panelTop + 74)) {
				showSettings = !showSettings;
				return true;
			}
		}
		// 线程操作按钮（选中线程时，优先级调整可用）
		if (selectedThreadId >= 0) {
			int panelTop = panelTop();
			if (hit(mx, my, 20, panelTop + 44)) {
				adjustThreadPriority(selectedThreadId, -1);
				return true;
			}
			if (hit(mx, my, 88, panelTop + 44)) {
				adjustThreadPriority(selectedThreadId, 1);
				return true;
			}
		}
		// 进程操作按钮（需选中进程）
		Process selected = selectedPid < 0 ? null : ProcessManager.getInstance().get(selectedPid);
		if (selected != null) {
			int panelTop = panelTop();
			if (hit(mx, my, 20, panelTop + 26)) {
				OperationEngine.getInstance().pause(selected, "本地用户");
				return true;
			}
			if (hit(mx, my, 78, panelTop + 26)) {
				OperationEngine.getInstance().resume(selected, "本地用户");
				return true;
			}
			if (hit(mx, my, 136, panelTop + 26)) {
				OperationEngine.getInstance().terminate(selected, "本地用户");
				return true;
			}
			if (hit(mx, my, 194, panelTop + 26)) {
				OperationEngine.getInstance().forceTerminate(selected, "本地用户");
				return true;
			}
			if (hit(mx, my, 274, panelTop + 26)) {
				OperationEngine.getInstance().restart(selected, "本地用户");
				return true;
			}
			if (hit(mx, my, 20, panelTop + 50)) {
				OperationEngine.getInstance().setPriority(selected, Math.max(1, selected.priority() - 1), "本地用户");
				return true;
			}
			if (hit(mx, my, 88, panelTop + 50)) {
				OperationEngine.getInstance().setPriority(selected, Math.min(5, selected.priority() + 1), "本地用户");
				return true;
			}
			if (hit(mx, my, 156, panelTop + 50, 84)) {
				OperationEngine.getInstance().start(selected, "本地用户");
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	private void exportOnce() {
		java.nio.file.Path file = PrcExporter.defaultDirectory()
			.resolve(PrcExporter.timestampedName("进程表", ".prc"));
		PrcExporter.getInstance().exportOnce(file);
	}

	private void toggleRealtime() {
		PrcExporter ex = PrcExporter.getInstance();
		if (ex.isRealtimeRunning()) {
			ex.stopRealtime();
		} else {
			ex.startRealtime(1000);
		}
	}

	private void exportVerify() {
		java.nio.file.Path file = PrcExporter.latestPrcFile();
		if (file != null) {
			PrcExporter.getInstance().read(file);
		}
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
		int viewportHeight = listBottom() - listTop();
		int maxScroll = Math.max(0, buildRows().size() * ROW_H - viewportHeight);
		scrollOffset = Math.clamp(scrollOffset - (int) scrollY * ROW_H, 0, maxScroll);
		return true;
	}

	private static boolean hit(double mx, double my, int x, int y) {
		return hit(mx, my, x, y, 56);
	}

	private static boolean hit(double mx, double my, int x, int y, int w) {
		return mx >= x && mx < x + w && my >= y && my < y + 18;
	}

	// ===== 无阴影文字（自定义纯色面板下，关闭 Minecraft 默认阴影，避免亮色主题重影） =====
	private void tmText(GuiGraphicsExtractor g, String str, int x, int y, int color) {
		g.text(this.font, str, x, y, color, false);
	}

	private void tmCentered(GuiGraphicsExtractor g, String str, int x, int y, int color) {
		g.text(this.font, str, x - this.font.width(str) / 2, y, color, false);
	}

	// ===== 过滤与格式化 =====
	private List<Process> filteredProcesses() {
		List<Process> all = new ArrayList<>(ProcessManager.getInstance().all());
		String keyword = searchBox == null ? "" : searchBox.getValue().trim();
		String lower = keyword.toLowerCase(java.util.Locale.ROOT);
		List<Process> result = new ArrayList<>();
		for (Process p : all) {
			if (activeTab == TAB_CLIENT && !isClient(p)) {
				continue;
			}
			if (activeTab == TAB_SERVER && !isServer(p)) {
				continue;
			}
			if (!matchesFilter(p, lower, keyword)) {
				continue;
			}
			result.add(p);
		}
		result.sort((a, b) -> Integer.compare(a.pid(), b.pid()));
		return result;
	}

	/** 搜索筛选：支持 `来源:xxx` / `状态:xxx` 前缀条件，否则按名称/PID 模糊匹配。 */
	private boolean matchesFilter(Process p, String lower, String keyword) {
		if (lower.isEmpty()) {
			return true;
		}
		if (lower.startsWith("来源:") || lower.startsWith("source:")) {
			String src = lower.substring(lower.indexOf(':') + 1).trim();
			return p.source().displayName().toLowerCase(java.util.Locale.ROOT).contains(src)
				|| p.source().id().toLowerCase(java.util.Locale.ROOT).contains(src);
		}
		if (lower.startsWith("状态:") || lower.startsWith("state:")) {
			String st = lower.substring(lower.indexOf(':') + 1).trim();
			return tr(stateKey(p.state())).contains(st) || stateKey(p.state()).contains(st);
		}
		return p.name().toLowerCase(java.util.Locale.ROOT).contains(lower)
			|| String.valueOf(p.pid()).equals(keyword);
	}

	private static boolean isClient(Process p) {
		return p.side() == ProcessSide.CLIENT;
	}

	private static boolean isServer(Process p) {
		return p.side() == ProcessSide.SERVER;
	}

	private static String stateKey(ProcessState state) {
		return switch (state) {
			case RUNNING -> "taskmanager.state.running";
			case PAUSED -> "taskmanager.state.paused";
			case TERMINATED -> "taskmanager.state.terminated";
			case PENDING_START -> "taskmanager.state.pending";
		};
	}

	private static int stateColor(Process p) {
		return switch (p.state()) {
			case RUNNING -> 0xFF55CC55;
			case PAUSED -> 0xFFDDAA22;
			case TERMINATED -> 0xFFCC5555;
			case PENDING_START -> 0xFF888888;
		};
	}

	private static String cpuText(Process p) {
		double cpu = p.usage().cpuUsage();
		return Double.isNaN(cpu) ? "N/A" : String.format("%.1f%%", cpu);
	}

	private static String memoryText(Process p) {
		if (p.usage().heapMemory() < 0) {
			// 实体进程为逻辑容器，无独立内存
			return "-";
		}
		// 全局进程共享 JVM 堆，不逐行重复显示数值，避免误导
		return "共享";
	}

	private static String formatBytes(long bytes) {
		if (bytes < 1024) {
			return bytes + " B";
		}
		if (bytes < 1024 * 1024) {
			return String.format("%.1f KB", bytes / 1024.0);
		}
		if (bytes < 1024L * 1024 * 1024) {
			return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
		}
		return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
	}

	private int heatColor(double percent) {
		if (Double.isNaN(percent)) {
			return textMuted();
		}
		if (percent > 80) {
			return 0xFFE05A5A;
		}
		if (percent > 50) {
			return 0xFFE0A55A;
		}
		if (percent > 20) {
			return 0xFFE0D05A;
		}
		return text();
	}

	// ===== 主题颜色 =====
	private String tr(String key) {
		return Component.translatable(key).getString();
	}

	private int bg() {
		return darkMode ? 0xC0101010 : 0xE6FFFFFF;
	}

	private int panel() {
		return darkMode ? 0xFF202020 : 0xFFE8E8E8;
	}

	private int button() {
		return darkMode ? 0xFF2A2A2A : 0xFFCCCCCC;
	}

	private int text() {
		return darkMode ? 0xFFFFFFFF : 0xFF202020;
	}

	private int textMuted() {
		return darkMode ? 0xFFAAAAAA : 0xFF606060;
	}

	private int accent() {
		return 0xFF3A6EA5;
	}
}
