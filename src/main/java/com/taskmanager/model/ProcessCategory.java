package com.taskmanager.model;

/**
 * 进程类别：实体类（对应游戏内实体）或全局类（系统级任务）。
 */
public enum ProcessCategory {
	/** 实体类：生物、NPC、玩家、掉落物实体、其他实体 */
	ENTITY,
	/** 全局类：世界 tick、渲染循环、网络 IO 等系统级任务 */
	GLOBAL
}
