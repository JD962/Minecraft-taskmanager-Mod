package com.taskmanager.remote;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 远程管理配置（静态单例）：优先级 系统属性 &gt; 配置文件(config/taskmanager.json) &gt; 默认值。
 * <p>
 * 默认绑定 0.0.0.0（远程可访问，token 随机保护），token 随机生成。
 * <b>端口默认跟随游戏端口 + 1</b>：未显式配置 port（或配为 0）时，远程管理端口 =
 * 游戏服务器端口 + 1（如游戏 25565 → 远程 25566）；显式配置 port 则用配置值。
 * token 支持运行时重置（{@code /taskmgr token reset}），重置后<b>永久有效</b>（持久化到
 * 配置文件，重启后仍保留），直到再次重置或手动修改配置。
 * <p>
 * 配置文件示例（config/taskmanager.json）：
 * <pre>{@code
 * {
 *   "bindHost": "0.0.0.0",
 *   "port": 0,
 *   "token": "自定义口令（留空则每次启动随机）"
 * }
 * }</pre>
 * {@code port} 为 0 或缺省 = 跟随游戏端口 + 1；写具体数字 = 固定该端口。
 */
public final class RemoteConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger("TaskManager/RemoteConfig");

	private static volatile String host = "0.0.0.0";
	/** 0 表示「跟随游戏端口 + 1」；&gt;0 表示显式固定端口。 */
	private static volatile int port = 0;
	private static volatile String token = "";

	private RemoteConfig() {
	}

	/** 启动时加载配置（在模组 onInitialize 调用）。 */
	public static void load() {
		JsonObject file = readFile();
		host = prop("taskmanager.remote.host", str(file, "bindHost", "0.0.0.0"));
		port = propInt("taskmanager.remote.port", intVal(file, "port", 0));
		token = prop("taskmanager.remote.token", str(file, "token", ""));
		if (token.isBlank()) {
			token = UUID.randomUUID().toString();
		}
	}

	public static String host() {
		return host;
	}

	/** 原始配置端口（0 = 跟随游戏端口）。 */
	public static int port() {
		return port;
	}

	/** 解析实际端口：显式配置（&gt;0）用之，否则跟随游戏端口 + 1。 */
	public static int resolvePort(int gamePort) {
		if (port > 0) {
			return port;
		}
		// 集成服务器（单人游戏）端口为 -1/0，无有效游戏端口，回退默认端口
		if (gamePort <= 0) {
			return TaskManagerServerConfig.DEFAULT_PORT;
		}
		int p = gamePort + 1;
		return p > 65535 ? TaskManagerServerConfig.DEFAULT_PORT : p;
	}

	/** 当前 token（供认证与 /taskmgr token 命令查看）。 */
	public static String token() {
		return token;
	}

	/** 重置 token 并持久化到配置文件（空值则随机生成）。返回新 token。 */
	public static synchronized String resetToken(String newToken) {
		if (newToken == null || newToken.isBlank()) {
			newToken = UUID.randomUUID().toString();
		}
		token = newToken;
		persist();
		LOGGER.info("[任务管理器] 远程管理 token 已重置，指纹: {}", Integer.toHexString(newToken.hashCode()));
		return newToken;
	}

	private static void persist() {
		Path p = FabricLoader.getInstance().getConfigDir().resolve("taskmanager.json");
		try {
			JsonObject obj = new JsonObject();
			obj.addProperty("bindHost", host);
			obj.addProperty("port", port);
			obj.addProperty("token", token);
			Files.writeString(p, obj.toString());
		} catch (Exception e) {
			LOGGER.warn("[任务管理器] 持久化 token 到 config/taskmanager.json 失败: {}", e.getMessage());
		}
	}

	private static JsonObject readFile() {
		Path p = FabricLoader.getInstance().getConfigDir().resolve("taskmanager.json");
		try {
			if (Files.exists(p)) {
				String s = Files.readString(p);
				if (!s.isBlank()) {
					return JsonParser.parseString(s).getAsJsonObject();
				}
			}
		} catch (Exception e) {
			LOGGER.warn("[任务管理器] 读取 config/taskmanager.json 失败，使用默认配置: {}", e.getMessage());
		}
		return new JsonObject();
	}

	private static String str(JsonObject o, String key, String def) {
		try {
			return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
		} catch (Exception ignored) {
			return def;
		}
	}

	private static int intVal(JsonObject o, String key, int def) {
		try {
			return o.has(key) ? o.get(key).getAsInt() : def;
		} catch (Exception ignored) {
			return def;
		}
	}

	private static String prop(String key, String def) {
		String v = System.getProperty(key);
		return v == null || v.isBlank() ? def : v;
	}

	private static int propInt(String key, int def) {
		String v = System.getProperty(key);
		if (v != null && !v.isBlank()) {
			try {
				return Integer.parseInt(v.trim());
			} catch (NumberFormatException ignored) {
			}
		}
		return def;
	}
}
