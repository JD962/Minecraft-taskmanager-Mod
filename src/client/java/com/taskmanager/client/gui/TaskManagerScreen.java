package com.taskmanager.client.gui;

import com.taskmanager.api.ProcessState;
import com.taskmanager.core.OperationEngine;
import com.taskmanager.core.OperationLog;
import com.taskmanager.core.ProcessManager;
import com.taskmanager.model.Process;
import com.taskmanager.model.ProcessCategory;
import com.taskmanager.model.ThreadInfo;
import com.taskmanager.sampling.MethodProfiler;
import com.taskmanager.sampling.ResourceSampler;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * 任务管理器主界面：独立自定义 UI（Blaze3D 抽象渲染）。
 * <p>
 * 支持：亮/暗主题切换、客户端/服务端/全部页签、搜索、进程树（展开线程/方法级）、
 * 热力色、操作按钮、操作日志、设置页。
 */
public class TaskManagerScreen extends Screen {
	private static final int TAB_CLIENT = 0;
	private static final int TAB_SERVER = 1;
	private static final int TAB_ALL = 2;
	private static final String[] TABS = {"客户端", "服务端", "全部"};

	private boolean darkMode = true;
	private int activeTab = TAB_ALL;
	private EditBox searchBox;
	private int selectedPid = -1;
	private final Set<Integer> expandedProcesses = new HashSet<>();
	private final Set<Integer> expandedThreads = new HashSet<>();
	private int scrollOffset = 0;
	private boolean showSettings = false;

	public TaskManagerScreen() {
		super(Component.literal("任务管理器"));
	}

	@Override
	protected void init() {
		this.searchBox = new EditBox(this.font, 20, 58, 240, 16, Component.literal("搜索"));
		this.searchBox.setValue("");
		this.addRenderableWidget(this.searchBox);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(0, 0, this.width, this.height, bg());
		graphics.fill(0, 0, this.width, 26, panel());
		graphics.centeredText(this.font, "任务管理器", this.width / 2, 8, text());
		renderThemeButton(graphics);
		renderTabs(graphics);
		renderProcessList(graphics);
		renderDetailPanel(graphics);
		renderLogs(graphics);
		if (showSettings) {
			renderSettings(graphics);
		}
	}

	// ===== 主题 =====
	private void renderThemeButton(GuiGraphicsExtractor graphics) {
		int x = this.width - 52;
		int y = 5;
		graphics.fill(x, y, x + 44, y + 16, button());
		graphics.centeredText(this.font, darkMode ? "暗" : "亮", x + 22, y + 4, text());
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
			graphics.centeredText(this.font, TABS[i], x + tabWidth / 2, y + 6, active ? 0xFFFFFF : textMuted());
		}
	}

	// ===== 进程列表 =====
	private void renderProcessList(GuiGraphicsExtractor graphics) {
		List<Process> processes = filteredProcesses();
		int x = 20;
		int y = 82;
		graphics.text(this.font, "PID", x, y, textMuted());
		graphics.text(this.font, "名称", x + 50, y, textMuted());
		graphics.text(this.font, "状态", x + 220, y, textMuted());
		graphics.text(this.font, "CPU", x + 300, y, textMuted());
		graphics.text(this.font, "内存", x + 380, y, textMuted());

		int listTop = y + 16;
		int listHeight = this.height - listTop - 150;
		int visibleStart = Math.max(0, scrollOffset);
		int maxVisible = Math.max(0, listHeight / 14);
		int row = 0;
		for (int i = visibleStart; i < processes.size() && row < maxVisible; i++, row++) {
			Process p = processes.get(i);
			int rowY = listTop + row * 14;
			if (p.pid() == selectedPid) {
				graphics.fill(15, rowY - 1, this.width - 15, rowY + 13, accent());
			}
			boolean expanded = expandedProcesses.contains(p.pid());
			graphics.text(this.font, (expanded ? "v " : "> ") + p.pid(), x, rowY, text());
			graphics.text(this.font, p.name(), x + 50, rowY, text());
			graphics.text(this.font, stateText(p), x + 220, rowY, stateColor(p));
			graphics.text(this.font, cpuText(p), x + 300, rowY, heatColor(p.usage().cpuUsage()));
			graphics.text(this.font, memoryText(p), x + 380, rowY, textMuted());
			rowY += 14;
			if (expanded) {
				rowY = renderThreads(graphics, p, x + 30, rowY, row, maxVisible, listTop);
			}
		}
		if (processes.isEmpty()) {
			graphics.text(this.font, "无匹配进程", x, listTop, textMuted());
		}
	}

	private int renderThreads(GuiGraphicsExtractor graphics, Process p, int x, int y, int row, int maxVisible, int listTop) {
		for (ThreadInfo thread : p.threads()) {
			if (row >= maxVisible) {
				return y;
			}
			int rowY = y;
			graphics.text(this.font, "- " + thread.threadName(), x, rowY, textMuted());
			graphics.text(this.font, cpuText2(thread), x + 230, rowY, heatColor(thread.usage().cpuUsage()));
			rowY += 14;
			row++;
			if (expandedThreads.contains((int) thread.threadId())) {
				Map<String, List<MethodProfiler.MethodNode>> snapshot = ResourceSampler.getInstance().methodSnapshot();
				List<MethodProfiler.MethodNode> methods = snapshot.get(thread.threadName());
				if (methods != null) {
					int limit = Math.min(6, methods.size());
					for (int j = 0; j < limit && row < maxVisible; j++, row++, rowY += 14) {
						MethodProfiler.MethodNode node = methods.get(j);
						graphics.text(this.font, String.format("    %s %.1f%%", node.methodName(), node.cpuRatio()), x + 16, rowY, textMuted());
					}
				}
			}
			y = rowY;
		}
		return y;
	}

	private static String cpuText2(ThreadInfo t) {
		double cpu = t.usage().cpuUsage();
		return Double.isNaN(cpu) ? "N/A" : String.format("%.1f%%", cpu);
	}

	// ===== 详情面板 + 操作按钮 =====
	private void renderDetailPanel(GuiGraphicsExtractor graphics) {
		Process selected = selectedPid < 0 ? null : ProcessManager.getInstance().get(selectedPid);
		int panelTop = this.height - 150;
		graphics.fill(0, panelTop, this.width, this.height, panel());
		if (selected == null) {
			graphics.text(this.font, "点击进程选中，展开线程/方法级详情", 20, panelTop + 8, textMuted());
			return;
		}
		graphics.text(this.font, String.format("PID %d | %s | 来源 %s | 优先级 %d",
			selected.pid(), selected.name(), selected.source().displayName(), selected.priority()), 20, panelTop + 8, text());
		renderActionButton(graphics, "暂停", 20, panelTop + 26);
		renderActionButton(graphics, "恢复", 78, panelTop + 26);
		renderActionButton(graphics, "终止", 136, panelTop + 26);
		renderActionButton(graphics, "强制终止", 194, panelTop + 26);
		renderActionButton(graphics, "重启", 274, panelTop + 26);
		renderActionButton(graphics, "优先级-", 20, panelTop + 50);
		renderActionButton(graphics, "优先级+", 88, panelTop + 50);
		renderActionButton(graphics, "启动", 156, panelTop + 50);
		renderActionButton(graphics, "日志", 20, panelTop + 74);
		renderActionButton(graphics, "设置", 78, panelTop + 74);
	}

	private void renderActionButton(GuiGraphicsExtractor graphics, String label, int x, int y) {
		graphics.fill(x, y, x + 56, y + 18, button());
		graphics.centeredText(this.font, label, x + 28, y + 5, text());
	}

	private boolean showLogs = false;

	// ===== 操作日志 =====
	private void renderLogs(GuiGraphicsExtractor graphics) {
		if (!showLogs) {
			return;
		}
		int panelTop = this.height - 150;
		int logTop = this.height / 3;
		graphics.fill(20, logTop, this.width - 20, panelTop - 4, bg());
		graphics.text(this.font, "操作日志", 24, logTop + 6, text());
		List<OperationLog> logs = OperationEngine.getInstance().logs();
		int y = logTop + 24;
		int count = 0;
		for (int i = logs.size() - 1; i >= 0 && count < 8; i--, count++, y += 12) {
			OperationLog log = logs.get(i);
			graphics.text(this.font, String.format("[%s] %s %s -> %s", time(log.timestamp()), log.action(), log.target(), log.result()), 24, y, textMuted());
		}
	}

	private static String time(long millis) {
		java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("HH:mm:ss");
		return fmt.format(new java.util.Date(millis));
	}

	// ===== 设置 =====
	private void renderSettings(GuiGraphicsExtractor graphics) {
		int cx = this.width / 2 - 100;
		int cy = this.height / 2 - 60;
		graphics.fill(cx, cy, cx + 200, cy + 120, panel());
		graphics.centeredText(this.font, "设置", cx + 100, cy + 6, text());
		graphics.text(this.font, "采样周期(ms): " + ResourceSampler.getInstance().intervalMs(), cx + 10, cy + 28, text());
		renderActionButton(graphics, "0.5s", cx + 10, cy + 44);
		renderActionButton(graphics, "1s", cx + 72, cy + 44);
		renderActionButton(graphics, "5s", cx + 120, cy + 44);
		renderActionButton(graphics, "关闭", cx + 72, cy + 90);
	}

	// ===== 交互 =====
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		int mx = (int) event.x();
		int my = (int) event.y();
		// 主题按钮
		if (mx >= this.width - 52 && mx <= this.width - 8 && my >= 5 && my <= 21) {
			darkMode = !darkMode;
			return true;
		}
		// 页签
		for (int i = 0; i < TABS.length; i++) {
			int x = 20 + i * 76;
			if (mx >= x && mx <= x + 72 && my >= 34 && my <= 54) {
				activeTab = i;
				scrollOffset = 0;
				return true;
			}
		}
		// 进程列表点击（选中/展开）
		List<Process> processes = filteredProcesses();
		int listTop = 98;
		int listHeight = this.height - listTop - 150;
		int maxVisible = Math.max(0, listHeight / 14);
		int idx = scrollOffset + (my - listTop) / 14;
		if (my >= listTop && my < listTop + maxVisible * 14 && idx >= 0 && idx < processes.size()) {
			Process p = processes.get(idx);
			if (selectedPid == p.pid()) {
				if (expandedProcesses.contains(p.pid())) {
					expandedProcesses.remove(p.pid());
				} else {
					expandedProcesses.add(p.pid());
				}
			}
			selectedPid = p.pid();
			return true;
		}
		// 操作按钮
		Process selected = selectedPid < 0 ? null : ProcessManager.getInstance().get(selectedPid);
		if (selected != null) {
			int panelTop = this.height - 150;
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
			if (hit(mx, my, 156, panelTop + 50)) {
				OperationEngine.getInstance().start(selected, "本地用户");
				return true;
			}
			if (hit(mx, my, 20, panelTop + 74)) {
				showLogs = !showLogs;
				return true;
			}
			if (hit(mx, my, 78, panelTop + 74)) {
				showSettings = !showSettings;
				return true;
			}
		}
		if (showSettings) {
			int cx = this.width / 2 - 100;
			int cy = this.height / 2 - 60;
			if (hit(mx, my, cx + 10, cy + 44)) {
				ResourceSampler.getInstance().setInterval(500);
				return true;
			}
			if (hit(mx, my, cx + 72, cy + 44)) {
				ResourceSampler.getInstance().setInterval(1000);
				return true;
			}
			if (hit(mx, my, cx + 120, cy + 44)) {
				ResourceSampler.getInstance().setInterval(5000);
				return true;
			}
			if (hit(mx, my, cx + 72, cy + 90)) {
				showSettings = false;
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
		scrollOffset = Math.max(0, scrollOffset - (int) scrollY);
		return true;
	}

	private static boolean hit(int mx, int my, int x, int y) {
		return mx >= x && mx <= x + 56 && my >= y && my <= y + 18;
	}

	// ===== 过滤与格式化 =====
	private List<Process> filteredProcesses() {
		List<Process> all = new ArrayList<>(ProcessManager.getInstance().all());
		String keyword = searchBox == null ? "" : searchBox.getValue().trim().toLowerCase();
		List<Process> result = new ArrayList<>();
		for (Process p : all) {
			if (activeTab == TAB_CLIENT && !isClient(p)) {
				continue;
			}
			if (activeTab == TAB_SERVER && !isServer(p)) {
				continue;
			}
			if (!keyword.isEmpty()
				&& !p.name().toLowerCase().contains(keyword)
				&& !String.valueOf(p.pid()).equals(keyword)) {
				continue;
			}
			result.add(p);
		}
		result.sort((a, b) -> Integer.compare(a.pid(), b.pid()));
		return result;
	}

	private static boolean isClient(Process p) {
		return p.category() == ProcessCategory.GLOBAL && (p.name().contains("渲染") || p.name().contains("客户端"));
	}

	private static boolean isServer(Process p) {
		return !isClient(p);
	}

	private static String stateText(Process p) {
		return switch (p.state()) {
			case RUNNING -> "运行";
			case PAUSED -> "已暂停";
			case TERMINATED -> "已终止";
			case PENDING_START -> "待启动";
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
		long mem = p.usage().totalMemory();
		if (mem < 0) {
			return "N/A";
		}
		if (mem < 1024) {
			return mem + " B";
		}
		if (mem < 1024 * 1024) {
			return String.format("%.1f KB", mem / 1024.0);
		}
		return String.format("%.1f MB", mem / (1024.0 * 1024.0));
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
