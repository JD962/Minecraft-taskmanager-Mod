package com.taskmanager.core;

import com.taskmanager.model.Process;
import com.taskmanager.model.ProcessSide;
import com.taskmanager.model.ProcessSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.Person;

/**
 * 模组/插件管理：列出所有已加载模组，维护启用/禁用状态。
 * <p>
 * Fabric 模组的「禁用」为逻辑禁用（标记 + 对应进程暂停）；模组功能是否真正停止，
 * 取决于模组是否通过适配接口配合（Java 无法卸载已加载的类）。
 */
public final class ModManager {
	private static final ModManager INSTANCE = new ModManager();

	/** 跳过的核心组件（非用户模组）。 */
	private static final Set<String> CORE_IDS = Set.of("minecraft", "java", "fabricloader", "fabric-api");

	private final Set<String> disabledMods = ConcurrentHashMap.newKeySet();
	private final Map<String, Process> modProcesses = new ConcurrentHashMap<>();

	private ModManager() {
	}

	public static ModManager getInstance() {
		return INSTANCE;
	}

	/** 模组信息。 */
	public record ModInfo(String id, String name, String author, boolean enabled) {
	}

	/** 将所有模组注册为进程节点（来源=模组名）。 */
	public void registerAllMods(ProcessManager processManager) {
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			String id = mod.getMetadata().getId();
			if (CORE_IDS.contains(id)) {
				continue;
			}
			String name = mod.getMetadata().getName();
			Process process = processManager.registerGlobal(name, ProcessSource.mod(id), ProcessSide.SERVER);
			modProcesses.put(id, process);
		}
	}

	/** 禁用模组（逻辑禁用：标记 + 暂停对应进程）。 */
	public boolean disable(String modId, String operator) {
		if (disabledMods.contains(modId)) {
			return false;
		}
		disabledMods.add(modId);
		Process process = modProcesses.get(modId);
		if (process != null) {
			OperationEngine.getInstance().pause(process, operator);
		}
		return true;
	}

	/** 启用模组（恢复对应进程）。 */
	public boolean enable(String modId, String operator) {
		if (!disabledMods.remove(modId)) {
			return false;
		}
		Process process = modProcesses.get(modId);
		if (process != null) {
			OperationEngine.getInstance().resume(process, operator);
		}
		return true;
	}

	public boolean isDisabled(String modId) {
		return disabledMods.contains(modId);
	}

	/** 列出所有模组信息（含作者与状态）。 */
	public List<ModInfo> listMods() {
		Collection<ModContainer> mods = FabricLoader.getInstance().getAllMods();
		List<ModInfo> result = new ArrayList<>();
		for (ModContainer mod : mods) {
			String id = mod.getMetadata().getId();
			if (CORE_IDS.contains(id)) {
				continue;
			}
			String name = mod.getMetadata().getName();
			String author = firstAuthor(mod.getMetadata().getAuthors());
			result.add(new ModInfo(id, name, author, !disabledMods.contains(id)));
		}
		result.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
		return result;
	}

	private static String firstAuthor(Collection<Person> authors) {
		return authors.stream().map(Person::getName).findFirst().orElse("未知");
	}
}
