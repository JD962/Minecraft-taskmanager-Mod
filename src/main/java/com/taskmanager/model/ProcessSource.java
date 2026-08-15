package com.taskmanager.model;

import java.util.Objects;

/**
 * 进程来源：游戏本体或某个模组。
 */
public final class ProcessSource {
	/** 游戏本体的来源标识 */
	public static final String GAME_ID = "minecraft";

	private final String id;
	private final String displayName;

	private ProcessSource(String id, String displayName) {
		this.id = id;
		this.displayName = displayName;
	}

	/** 游戏本体来源。 */
	public static ProcessSource game() {
		return new ProcessSource(GAME_ID, "游戏本体");
	}

	/** 模组来源。 */
	public static ProcessSource mod(String modId) {
		return new ProcessSource(modId, modId);
	}

	public String id() {
		return id;
	}

	public String displayName() {
		return displayName;
	}

	public boolean isGame() {
		return GAME_ID.equals(id);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof ProcessSource that)) return false;
		return Objects.equals(id, that.id);
	}

	@Override
	public int hashCode() {
		return Objects.hash(id);
	}

	@Override
	public String toString() {
		return displayName;
	}
}
