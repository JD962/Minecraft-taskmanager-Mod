package com.taskmanager.remote;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

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

	public static JsonObject listResponse(JsonElement id, List<ProcessInfo> processes, OverviewInfo overview) {
		JsonObject o = ok("list", id);
		o.add("data", toJsonArray(processes));
		if (overview != null) {
			o.add("overview", toJson(overview));
		}
		return o;
	}

	public static JsonObject toJson(OverviewInfo v) {
		JsonObject o = new JsonObject();
		o.addProperty("processCpu", v.processCpu());
		o.addProperty("systemCpu", v.systemCpu());
		o.addProperty("heapUsed", v.heapUsed());
		o.addProperty("heapCommitted", v.heapCommitted());
		o.addProperty("gpuUsage", v.gpuUsage());
		o.addProperty("netIn", v.netIn());
		o.addProperty("netOut", v.netOut());
		o.addProperty("diskReadRate", v.diskReadRate());
		o.addProperty("diskWriteRate", v.diskWriteRate());
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
		o.addProperty("source", p.source());
		o.addProperty("category", p.category());
		o.addProperty("subCategory", p.subCategory());
		o.addProperty("side", p.side());
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

	/** 生成随机 nonce（32 字节，hex 编码），用于认证挑战。 */
	public static String randomNonce() {
		byte[] b = new byte[32];
		new SecureRandom().nextBytes(b);
		return HexFormat.of().formatHex(b);
	}

	/** HMAC-SHA256(key, message) 的 hex 结果，用于挑战-应答认证（token 不经过网络明文）。 */
	public static String hmacSha256(String key, String message) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception e) {
			throw new IllegalStateException("HMAC-SHA256 unavailable", e);
		}
	}
}
