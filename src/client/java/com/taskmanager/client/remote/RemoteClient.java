package com.taskmanager.client.remote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.taskmanager.remote.OperationResult;
import com.taskmanager.remote.OverviewInfo;
import com.taskmanager.remote.ProcessAction;
import com.taskmanager.remote.ProcessInfo;
import com.taskmanager.remote.Protocol;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 远程管理客户端：连接远程 TaskManager 服务端（TCP + token + JSON 行协议），
 * 提供 {@link #list()} 拉取进程表与 {@link #operate(ProcessAction, long, String)} 远程操作。
 * <p>
 * 运行在客户端侧，用于「实例选择器」与「独立远程客户端 GUI」连接远程服务端。
 */
public final class RemoteClient {
	private static final Logger LOGGER = LoggerFactory.getLogger("TaskManager/RemoteClient");

	private final String host;
	private final int port;
	private final String token;
	private final AtomicLong idSeq = new AtomicLong();
	private final Map<String, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
	private final List<Consumer<List<ProcessInfo>>> listeners = new CopyOnWriteArrayList<>();

	private EventLoopGroup group;
	private volatile Channel channel;
	private volatile boolean connected;
	/** 认证结果（challenge-应答完成后 complete true/false）。 */
	private volatile CompletableFuture<Boolean> authFuture;
	/** 最近一次 list 响应携带的远程概览指标。 */
	private volatile OverviewInfo lastOverview = OverviewInfo.EMPTY;

	public RemoteClient(String host, int port, String token) {
		this.host = host;
		this.port = port;
		this.token = token;
	}

	public String host() {
		return host;
	}

	public int port() {
		return port;
	}

	public boolean isConnected() {
		return connected && channel != null && channel.isActive();
	}

	/** 连接并认证。返回是否成功（失败时内部已清理）。 */
	public synchronized boolean connect() {
		if (isConnected()) {
			return true;
		}
		group = new NioEventLoopGroup(1, r -> {
			Thread t = new Thread(r, "tm-remote-client");
			t.setDaemon(true);
			return t;
		});
		authFuture = new CompletableFuture<>();
		try {
			Bootstrap b = new Bootstrap()
				.group(group)
				.channel(NioSocketChannel.class)
				.handler(new ChannelInitializer<SocketChannel>() {
					@Override
					protected void initChannel(SocketChannel ch) {
						ch.pipeline().addLast("framer", new LineBasedFrameDecoder(1 << 20, true, true));
						ch.pipeline().addLast("decoder", new StringDecoder(StandardCharsets.UTF_8));
						ch.pipeline().addLast("encoder", new StringEncoder(StandardCharsets.UTF_8));
						ch.pipeline().addLast("handler", new ClientHandler());
					}
				});
			channel = b.connect(host, port).sync().channel();
			connected = true;
			// 认证走挑战-应答：等待 ClientHandler 收到 challenge、算 HMAC 回传、收到 auth 响应后 complete
			try {
				return authFuture.get(5, TimeUnit.SECONDS);
			} catch (Exception e) {
				LOGGER.warn("[TM] 远程认证未完成 {}:{}: {}", host, port, e.getMessage());
				disconnect();
				return false;
			}
		} catch (Exception e) {
			LOGGER.warn("[TM] 远程连接失败 {}:{}: {}", host, port, e.getMessage());
			disconnect();
			return false;
		}
	}

	/** 拉取远程进程表快照。 */
	public CompletableFuture<List<ProcessInfo>> list() {
		if (!isConnected()) {
			return CompletableFuture.completedFuture(List.of());
		}
		JsonObject req = new JsonObject();
		req.addProperty("type", "list");
		return send(req, null).thenApply(resp -> {
			List<ProcessInfo> out = new ArrayList<>();
			JsonElement data = resp.get("data");
			if (data instanceof JsonArray arr) {
				for (JsonElement e : arr) {
					if (e instanceof JsonObject o) {
					long pid = o.has("pid") ? o.get("pid").getAsLong() : -1L;
					String name = o.has("name") ? o.get("name").getAsString() : "";
					String source = o.has("source") ? o.get("source").getAsString() : "";
					String category = o.has("category") ? o.get("category").getAsString() : "";
					String subCategory = o.has("subCategory") ? o.get("subCategory").getAsString() : "";
					String side = o.has("side") ? o.get("side").getAsString() : "";
					String state = o.has("state") ? o.get("state").getAsString() : "unknown";
					double cpu = o.has("cpu") ? o.get("cpu").getAsDouble() : Double.NaN;
					long memory = o.has("memory") ? o.get("memory").getAsLong() : -1L;
					out.add(new ProcessInfo(pid, name, source, category, subCategory, side, state, cpu, memory));
					}
				}
			}
			if (resp.get("overview") instanceof JsonObject ovo) {
				lastOverview = parseOverview(ovo);
			}
			for (Consumer<List<ProcessInfo>> l : listeners) {
				l.accept(out);
			}
			return out;
		});
	}

	/** 最近一次 list 响应携带的远程概览指标。 */
	public OverviewInfo lastOverview() {
		return lastOverview;
	}

	private static OverviewInfo parseOverview(JsonObject o) {
		return new OverviewInfo(
			o.has("processCpu") ? o.get("processCpu").getAsDouble() : Double.NaN,
			o.has("systemCpu") ? o.get("systemCpu").getAsDouble() : Double.NaN,
			o.has("heapUsed") ? o.get("heapUsed").getAsLong() : -1L,
			o.has("heapCommitted") ? o.get("heapCommitted").getAsLong() : -1L,
			o.has("gpuUsage") ? o.get("gpuUsage").getAsDouble() : Double.NaN,
			o.has("netIn") ? o.get("netIn").getAsLong() : -1L,
			o.has("netOut") ? o.get("netOut").getAsLong() : -1L,
			o.has("diskReadRate") ? o.get("diskReadRate").getAsLong() : -1L,
			o.has("diskWriteRate") ? o.get("diskWriteRate").getAsLong() : -1L);
	}

	/** 远程操作：暂停/恢复/终止/强制终止/重启/启动。 */
	public CompletableFuture<OperationResult> operate(ProcessAction action, long pid, String operator) {
		if (!isConnected()) {
			return CompletableFuture.completedFuture(OperationResult.fail("not connected"));
		}
		JsonObject req = new JsonObject();
		req.addProperty("type", "operate");
		req.addProperty("action", action.wire());
		req.addProperty("pid", pid);
		if (operator != null && !operator.isBlank()) {
			req.addProperty("operator", operator);
		}
		return send(req, null).thenApply(resp -> {
			boolean success = resp.has("success") && resp.get("success").getAsBoolean();
			String message = resp.has("message") ? resp.get("message").getAsString() : "";
			return success ? OperationResult.ok() : OperationResult.fail(message);
		});
	}

	/** 注册进程表更新监听（每次 list 响应后回调）。 */
	public void addListener(Consumer<List<ProcessInfo>> listener) {
		if (listener != null) {
			listeners.add(listener);
		}
	}

	public void disconnect() {
		connected = false;
		Channel ch = channel;
		channel = null;
		if (ch != null) {
			ch.close();
		}
		EventLoopGroup g = group;
		group = null;
		if (g != null) {
			g.shutdownGracefully(0, 2, TimeUnit.SECONDS);
		}
		for (CompletableFuture<JsonObject> f : pending.values()) {
			f.completeExceptionally(new IllegalStateException("disconnected"));
		}
		pending.clear();
	}

	/** 发送请求，返回带 id 匹配的响应 future。 */
	private CompletableFuture<JsonObject> send(JsonObject req, String id) {
		if (id == null) {
			id = String.valueOf(idSeq.incrementAndGet());
			req.addProperty("id", id);
		}
		CompletableFuture<JsonObject> future = new CompletableFuture<>();
		pending.put(id, future);
		if (channel != null && channel.isActive()) {
			// 服务端用 LineBasedFrameDecoder 按换行符分割帧，必须追加 '\n'，否则消息无法被解析
			channel.writeAndFlush(req.toString() + "\n");
		} else {
			future.completeExceptionally(new IllegalStateException("not connected"));
			pending.remove(id);
		}
		return future;
	}

	/** 客户端响应处理器：匹配 id，完成对应 future。 */
	private final class ClientHandler extends SimpleChannelInboundHandler<String> {
		@Override
		protected void channelRead0(ChannelHandlerContext ctx, String line) {
			try {
				JsonElement parsed = JsonParser.parseString(line.trim());
				if (!parsed.isJsonObject()) {
					return;
				}
				JsonObject obj = parsed.getAsJsonObject();
				String type = obj.has("type") && !obj.get("type").isJsonNull() ? obj.get("type").getAsString() : "";
				if ("challenge".equals(type)) {
					// 收到认证挑战：用 token 对 nonce 做 HMAC-SHA256 回传（token 不明文上网）
					String nonce = obj.has("nonce") ? obj.get("nonce").getAsString() : "";
					String response = Protocol.hmacSha256(token, nonce);
					JsonObject auth = new JsonObject();
					auth.addProperty("type", "auth");
					auth.addProperty("response", response);
					auth.addProperty("operator", "远程管理员");
					if (channel != null && channel.isActive()) {
						channel.writeAndFlush(auth.toString() + "\n");
					}
					return;
				}
				if ("auth".equals(type)) {
					// 认证结果：complete authFuture，connect() 据此返回成功/失败
					boolean success = obj.has("success") && obj.get("success").getAsBoolean();
					CompletableFuture<Boolean> f = authFuture;
					if (f != null) {
						f.complete(success);
					}
					return;
				}
				String id = obj.has("id") && !obj.get("id").isJsonNull() ? obj.get("id").getAsString() : null;
				if (id != null) {
					CompletableFuture<JsonObject> f = pending.remove(id);
					if (f != null) {
						f.complete(obj);
					}
				}
			} catch (Exception ignored) {
				// 忽略无法解析的响应行
			}
		}

		@Override
		public void channelInactive(ChannelHandlerContext ctx) {
			connected = false;
			CompletableFuture<Boolean> af = authFuture;
			if (af != null) {
				af.complete(false);
			}
			for (CompletableFuture<JsonObject> f : pending.values()) {
				f.completeExceptionally(new IllegalStateException("connection lost"));
			}
			pending.clear();
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
			LOGGER.warn("[TM] 远程客户端连接异常: {}", cause.toString());
			ctx.close();
		}
	}
}
