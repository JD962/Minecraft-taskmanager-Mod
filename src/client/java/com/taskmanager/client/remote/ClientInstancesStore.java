package com.taskmanager.client.remote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;

/**
 * 客户端远程实例的本地持久化：保存到 {@code config/taskmanager-client.json}，重启后自动加载。
 * <p>
 * token 用 XOR + Base64 混淆存储（本地无安全密钥环境，防明文查看，非强加密）。
 * 认证走挑战-应答，token 永不通过网络明文传输。
 */
public final class ClientInstancesStore {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("taskmanager-client.json");
	private static final byte[] KEY = "taskmanager-client-obfuscation-key-v1".getBytes(StandardCharsets.UTF_8);

	/** 一条已保存的实例信息。 */
	public record Entry(String name, String host, int port, String token) {
	}

	private ClientInstancesStore() {
	}

	/** 从配置文件加载实例列表；文件不存在或解析失败返回空列表。 */
	public static List<Entry> load() {
		try {
			if (!Files.exists(FILE)) {
				return List.of();
			}
			JsonElement root = JsonParser.parseString(Files.readString(FILE));
			if (!root.isJsonArray()) {
				return List.of();
			}
			List<Entry> out = new ArrayList<>();
			for (JsonElement e : root.getAsJsonArray()) {
				if (!e.isJsonObject()) {
					continue;
				}
				JsonObject o = e.getAsJsonObject();
				String host = opt(o, "host");
				String token = deobfuscate(opt(o, "token"));
				if (host.isBlank() || token.isBlank()) {
					continue;
				}
				int port = o.has("port") ? o.get("port").getAsInt() : 25566;
				out.add(new Entry(opt(o, "name"), host, port, token));
			}
			return out;
		} catch (Exception ignored) {
			return List.of();
		}
	}

	/** 把实例列表持久化到配置文件（token 混淆）。 */
	public static void save(List<RemoteInstance> instances) {
		try {
			JsonArray arr = new JsonArray();
			for (RemoteInstance inst : instances) {
				JsonObject o = new JsonObject();
				o.addProperty("name", inst.name());
				o.addProperty("host", inst.host());
				o.addProperty("port", inst.port());
				o.addProperty("token", obfuscate(inst.token()));
				arr.add(o);
			}
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, arr.toString());
		} catch (Exception ignored) {
			// 持久化失败不阻断正常使用
		}
	}

	private static String opt(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e == null || e.isJsonNull() ? "" : e.getAsString();
	}

	private static String obfuscate(String token) {
		byte[] b = token.getBytes(StandardCharsets.UTF_8);
		for (int i = 0; i < b.length; i++) {
			b[i] ^= KEY[i % KEY.length];
		}
		return Base64.getEncoder().encodeToString(b);
	}

	private static String deobfuscate(String data) {
		if (data == null || data.isBlank()) {
			return "";
		}
		try {
			byte[] b = Base64.getDecoder().decode(data);
			for (int i = 0; i < b.length; i++) {
				b[i] ^= KEY[i % KEY.length];
			}
			return new String(b, StandardCharsets.UTF_8);
		} catch (Exception ignored) {
			return "";
		}
	}
}
