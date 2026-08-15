package com.taskmanager.remote;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import java.util.List;

/** JSON 行协议的报文构造与读取工具，线程安全。 */
public final class Protocol {
	private static final Gson GSON = new Gson();

	private Protocol() {
	}

	public static String toLine(JsonObject obj) {
		return GSON.toJson(obj);
	}

	public static ChannelFuture send(Channel ch, JsonObject obj) {
		return ch.writeAndFlush(toLine(obj));
	}

	private static JsonObject base(String type, JsonElement id) {
		JsonObject o = new JsonObject();
		o.addProperty("type", type);
		if (id != null && !id.isJsonNull()) {
			o.add("id", id);
		}
		return o;
	}

	public static JsonObject ok(String type, JsonElement id) {
		JsonObject o = base(type, id);
		o.addProperty("success", true);
		return o;
	}

	public static JsonObject result(String type, JsonElement id, OperationResult r) {
		JsonObject o = base(type, id);
		o.addProperty("success", r.success());
		o.addProperty("message", r.message());
		return o;
	}

	public static JsonObject error(String type, JsonElement id, String code, String message) {
		JsonObject o = base(type == null ? "error" : type, id);
		o.addProperty("success", false);
		o.addProperty("code", code);
		o.addProperty("message", message == null ? "" : message);
		return o;
	}

	public static JsonObject listResponse(JsonElement id, List<ProcessInfo> processes) {
		JsonObject o = ok("list", id);
		o.add("data", toJsonArray(processes));
		return o;
	}

	public static JsonArray toJsonArray(List<ProcessInfo> processes) {
		JsonArray arr = new JsonArray();
		if (processes != null) {
			for (ProcessInfo p : processes) {
				if (p != null) arr.add(toJson(p));
			}
		}
		return arr;
	}

	public static JsonObject toJson(ProcessInfo p) {
		JsonObject o = new JsonObject();
		o.addProperty("pid", p.pid());
		o.addProperty("name", p.name());
		o.addProperty("state", p.state());
		o.addProperty("cpu", p.cpu());
		o.addProperty("memory", p.memory());
		return o;
	}

	public static String optString(JsonObject o, String key) {
		JsonElement e = o.get(key);
		if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return null;
		return e.getAsString();
	}

	public static Long optLong(JsonObject o, String key) {
		JsonElement e = o.get(key);
		if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return null;
		JsonPrimitive p = e.getAsJsonPrimitive();
		try {
			if (p.isNumber()) return p.getAsLong();
			if (p.isString()) return Long.parseLong(p.getAsString().trim());
		} catch (NumberFormatException ignored) {
		}
		return null;
	}

	public static JsonElement optId(JsonObject o) {
		JsonElement e = o.get("id");
		return (e == null || e.isJsonNull()) ? null : e;
	}
}
