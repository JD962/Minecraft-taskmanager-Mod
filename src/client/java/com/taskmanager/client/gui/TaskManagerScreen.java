package com.taskmanager.client.gui;

import com.taskmanager.core.ProcessManager;
import com.taskmanager.model.Process;
import com.taskmanager.model.ProcessCategory;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 任务管理器主界面：独立自定义 UI（基于 Blaze3D 抽象渲染，不依赖原版容器 UI）。
 * <p>
 * 顶部页签「客户端 / 服务端 / 全部」切换显示对象；支持搜索与进程选中。
 */
public class TaskManagerScreen extends Screen {
	private static final int TAB_CLIENT = 0;
	private static final int TAB_SERVER = 1;
	private static final int TAB_ALL = 2;

	private static final String[] TABS = {"客户端", "服务端", "全部"};

	private int activeTab = TAB_ALL;
	private String searchText = "";
	private int selectedPid = -1;
	private int scrollOffset = 0;

	public TaskManagerScreen() {
		super(Component.literal("任务管理器"));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		renderBackground(graphics);
		graphics.centeredText(this.font, "任务管理器", this.width / 2, 10, 0xFFFFFF);
		renderTabs(graphics);
		renderSearchBox(graphics);
		renderProcessList(graphics);
	}

	private void renderBackground(GuiGraphicsExtractor graphics) {
		graphics.fill(0, 0, this.width, this.height, 0xC0101010);
		graphics.fill(0, 0, this.width, 26, 0xFF202020);
	}

	private void renderTabs(GuiGraphicsExtractor graphics) {
		int tabWidth = 72;
		int startX = 20;
		int y = 34;
		for (int i = 0; i < TABS.length; i++) {
			int x = startX + i * (tabWidth + 4);
			boolean active = i == activeTab;
			graphics.fill(x, y, x + tabWidth, y + 20, active ? 0xFF3A6EA5 : 0xFF2A2A2A);
			graphics.centeredText(this.font, TABS[i], x + tabWidth / 2, y + 6, active ? 0xFFFFFF : 0xAAAAAA);
		}
	}

	private void renderSearchBox(GuiGraphicsExtractor graphics) {
		int x = 20;
		int y = 58;
		int width = Math.min(240, this.width - 40);
		graphics.fill(x, y, x + width, y + 16, 0xFF2A2A2A);
		String text = searchText.isEmpty() ? "搜索名称 / PID..." : searchText;
		int color = searchText.isEmpty() ? 0x888888 : 0xFFFFFF;
		graphics.text(this.font, text, x + 6, y + 4, color);
	}

	private void renderProcessList(GuiGraphicsExtractor graphics) {
		List<Process> processes = filteredProcesses();
		int x = 20;
		int y = 82;
		graphics.text(this.font, "PID", x, y, 0x888888);
		graphics.text(this.font, "名称", x + 50, y, 0x888888);
		graphics.text(this.font, "状态", x + 250, y, 0x888888);
		graphics.text(this.font, "CPU", x + 330, y, 0x888888);
		graphics.text(this.font, "内存", x + 410, y, 0x888888);
		y += 16;

		int visibleStart = Math.max(0, scrollOffset);
		int maxVisible = (this.height - y - 10) / 14;
		int visibleEnd = Math.min(processes.size(), visibleStart + maxVisible);
		for (int i = visibleStart; i < visibleEnd; i++) {
			Process p = processes.get(i);
			boolean selected = p.pid() == selectedPid;
			if (selected) {
				graphics.fill(15, y - 1, this.width - 15, y + 13, 0xFF3A6EA5);
			}
			graphics.text(this.font, String.valueOf(p.pid()), x, y, 0xFFFFFF);
			graphics.text(this.font, p.name(), x + 50, y, 0xFFFFFF);
			graphics.text(this.font, stateText(p), x + 250, y, stateColor(p));
			graphics.text(this.font, cpuText(p), x + 330, y, 0xCCCCCC);
			graphics.text(this.font, memoryText(p), x + 410, y, 0xCCCCCC);
			y += 14;
		}
		if (processes.isEmpty()) {
			graphics.text(this.font, "无匹配进程", x, y, 0x888888);
		}
	}

	private List<Process> filteredProcesses() {
		List<Process> all = new ArrayList<>(ProcessManager.getInstance().all());
		List<Process> result = new ArrayList<>();
		String keyword = searchText.trim().toLowerCase();
		for (Process p : all) {
			if (activeTab == TAB_CLIENT && !isClient(p)) {
				continue;
			}
			if (activeTab == TAB_SERVER && !isServer(p)) {
				continue;
			}
			if (!keyword.isEmpty()
				&& !p.name().toLowerCase().contains(keyword)
				&& !String.valueOf(p.pid()).equals(searchText.trim())) {
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
}
