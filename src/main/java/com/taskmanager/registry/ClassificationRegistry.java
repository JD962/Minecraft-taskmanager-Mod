package com.taskmanager.registry;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import net.minecraft.world.entity.Entity;

/**
 * 自定义实体分类注册表：模组/游戏可注册自定义分类（名称 + 实体匹配条件）。
 * <p>
 * 内置分类（玩家/掉落物实体/生物/其他实体）由 {@code ProcessManager.classifyEntity} 直接处理；
 * 注册的自定义分类不直接平铺展示，而是收进 UI 的「更多」按钮二级窗口。
 */
public final class ClassificationRegistry {
	/** 一个自定义分类：名称 + 实体匹配条件。 */
	public record Category(String name, Predicate<Entity> matcher) {
	}

	private static final List<Category> CATEGORIES = new CopyOnWriteArrayList<>();

	private ClassificationRegistry() {
	}

	/** 注册自定义实体分类。名称重复返回 false。 */
	public static boolean register(String name, Predicate<Entity> matcher) {
		if (name == null || matcher == null) {
			return false;
		}
		for (Category c : CATEGORIES) {
			if (c.name().equals(name)) {
				return false;
			}
		}
		CATEGORIES.add(new Category(name, matcher));
		return true;
	}

	/** 尝试用注册的分类匹配实体，返回分类名；无匹配返回 null。 */
	public static String match(Entity entity) {
		if (entity == null) {
			return null;
		}
		for (Category c : CATEGORIES) {
			try {
				if (c.matcher().test(entity)) {
					return c.name();
				}
			} catch (Exception ignored) {
				// 匹配器抛异常时忽略该分类，避免单个适配影响整体
			}
		}
		return null;
	}

	/** 所有已注册的分类（不可变视图）。 */
	public static List<Category> categories() {
		return List.copyOf(CATEGORIES);
	}
}
