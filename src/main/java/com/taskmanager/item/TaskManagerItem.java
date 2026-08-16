package com.taskmanager.item;

import com.taskmanager.client.bridge.TaskManagerClientBridge;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/**
 * 任务管理器终端物品：右键打开任务管理器 GUI（原版容器 UI 的进入渠道）。
 * <p>
 * 拆分模组后，服务端与客户端各自注册一次（注册表需两端一致），故抽成静态
 * {@link #register()} 懒加载方法，两端入口分别调用。
 */
public class TaskManagerItem extends Item {
	public static final ResourceKey<Item> TERMINAL_KEY = ResourceKey.create(Registries.ITEM,
		Identifier.fromNamespaceAndPath("taskmanager", "task_manager_terminal"));

	private static Item terminal;

	public TaskManagerItem(Properties properties) {
		super(properties);
	}

	/** 注册终端物品（服务端与客户端各调用一次，重复调用只注册一次）。 */
	public static Item register() {
		if (terminal == null) {
			terminal = Registry.register(BuiltInRegistries.ITEM, TERMINAL_KEY,
				new TaskManagerItem(new Item.Properties().setId(TERMINAL_KEY)));
		}
		return terminal;
	}

	/** 已注册的终端物品实例（未注册时为 null）。 */
	public static Item terminal() {
		return terminal;
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (level.isClientSide()) {
			TaskManagerClientBridge.open();
		}
		return InteractionResult.SUCCESS;
	}
}
