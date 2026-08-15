package com.taskmanager.remote;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** 协议中 operate.action 的合法取值。 */
public enum ProcessAction {
	PAUSE("pause"),
	RESUME("resume"),
	TERMINATE("terminate"),
	FORCE_TERMINATE("force_terminate"),
	RESTART("restart"),
	START("start");

	private static final Map<String, ProcessAction> BY_WIRE = new HashMap<>();

	static {
		for (ProcessAction a : values()) {
			BY_WIRE.put(a.wire, a);
		}
	}

	private final String wire;

	ProcessAction(String wire) {
		this.wire = wire;
	}

	public String wire() {
		return wire;
	}

	public static ProcessAction fromWire(String s) {
		return s == null ? null : BY_WIRE.get(s.toLowerCase(Locale.ROOT));
	}
}
