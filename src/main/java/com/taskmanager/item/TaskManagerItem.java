package com.taskmanager.item;

import com.taskmanager.client.bridge.TaskManagerClientBridge;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/**
 * 任务管理器终端物品：右键打开任务管理器 GUI（原版容器 UI 的进入渠道）。
 */
public class TaskManagerItem extends Item {
	public TaskManagerItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (level.isClientSide()) {
			TaskManagerClientBridge.open();
		}
		return InteractionResult.SUCCESS;
	}
}
