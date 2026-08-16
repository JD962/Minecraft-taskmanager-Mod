package com.taskmanager.remote;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.ScheduledFuture;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 每个连接一个实例，持有该连接的认证超时任务。 */
public final class ServerHandler extends SimpleChannelInboundHandler<String> {
	private static final Logger LOGGER = LoggerFactory.getLogger("TaskManager/Handler");

	public static final AttributeKey<Boolean> AUTHED = AttributeKey.valueOf(ServerHandler.class, "authenticated");
	public static final AttributeKey<String> OPERATOR = AttributeKey.valueOf(ServerHandler.class, "operator");
	public static final AttributeKey<String> NONCE = AttributeKey.valueOf(ServerHandler.class, "nonce");

	private final TaskManagerServerConfig config;
	private final ProcessDataProvider provider;
	private final ExecutorService blockingPool;
	private final ChannelGroup clients;
	private ScheduledFuture<?> authTimeoutTask;

	public ServerHandler(TaskManagerServerConfig config, ProcessDataProvider provider,
	                     ExecutorService blockingPool, ChannelGroup clients) {
		this.config = config;
		this.provider = provider;
		this.blockingPool = blockingPool;
		this.clients = clients;
	}

	@Override
	public void channelActive(ChannelHandlerContext ctx) {
		clients.add(ctx.channel());
		// 认证挑战：生成随机 nonce 发给客户端，客户端用 token 做 HMAC 回传（token 不经过网络）
		String nonce = Protocol.randomNonce();
		ctx.channel().attr(NONCE).set(nonce);
		JsonObject challenge = new JsonObject();
		challenge.addProperty("type", "challenge");
		challenge.addProperty("nonce", nonce);
		Protocol.send(ctx.channel(), challenge);
		authTimeoutTask = ctx.executor().schedule(() -> {
			if (!isAuthed(ctx)) {
				closeWith(ctx, Protocol.error("auth", null, "auth_timeout", "authentication timeout"));
			}
		}, config.authTimeoutSeconds(), TimeUnit.SECONDS);
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) {
		cancelAuthTimeout();
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		if (cause instanceof TooLongFrameException) {
			closeWith(ctx, Protocol.error(null, null, "frame_too_long", "message exceeds limit"));
			return;
		}
		LOGGER.warn("connection error from {}: {}", ctx.channel().remoteAddress(), cause.toString());
		ctx.close();
	}

	@Override
	protected void channelRead0(ChannelHandlerContext ctx, String line) {
		String raw = line.trim();
		if (raw.isEmpty()) {
			return;
		}
		JsonObject req;
		try {
			JsonElement parsed = JsonParser.parseString(raw);
			if (!parsed.isJsonObject()) {
				throw new IllegalStateException("root is not a json object");
			}
			req = parsed.getAsJsonObject();
		} catch (Exception e) {
			Protocol.send(ctx.channel(), Protocol.error(null, null, "bad_request", "invalid json line"));
			return;
		}

		JsonElement id = Protocol.optId(req);
		String type = Protocol.optString(req, "type");
		if (type == null) {
			Protocol.send(ctx.channel(), Protocol.error(null, id, "bad_request", "missing field: type"));
			return;
		}

		if (!isAuthed(ctx)) {
			if (!"auth".equals(type)) {
				closeWith(ctx, Protocol.error(type, id, "unauthorized", "auth required as first message"));
				return;
			}
			handleAuth(ctx, req, id);
			return;
		}

		switch (type) {
			case "auth" -> Protocol.send(ctx.channel(), Protocol.ok("auth", id));
			case "ping" -> Protocol.send(ctx.channel(), Protocol.ok("pong", id));
			case "list" -> handleList(ctx, id);
			case "operate" -> handleOperate(ctx, req, id);
			default -> Protocol.send(ctx.channel(), Protocol.error(type, id, "unknown_type", "unsupported type: " + type));
		}
	}

	private void handleAuth(ChannelHandlerContext ctx, JsonObject req, JsonElement id) {
		String response = Protocol.optString(req, "response");
		String nonce = ctx.channel().attr(NONCE).get();
		// 挑战-应答：客户端用 token 对 nonce 做 HMAC-SHA256，服务端用自身 token 复算比对（动态读取，支持运行时重置）
		if (response == null || nonce == null
			|| !constantTimeEquals(response, Protocol.hmacSha256(RemoteConfig.token(), nonce))) {
			closeWith(ctx, Protocol.error("auth", id, "auth_failed", "invalid token"));
			return;
		}
		cancelAuthTimeout();
		ctx.channel().attr(AUTHED).set(Boolean.TRUE);
		String operator = Protocol.optString(req, "operator");
		if (operator != null && !operator.isBlank()) {
			ctx.channel().attr(OPERATOR).set(operator);
		}
		Protocol.send(ctx.channel(), Protocol.ok("auth", id));
	}

	private static boolean constantTimeEquals(String a, String b) {
		return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
	}

	private void handleList(ChannelHandlerContext ctx, JsonElement id) {
		submit(ctx, "list", id, () -> {
			List<ProcessInfo> processes = provider.listProcesses();
			OverviewInfo overview = provider.overview();
			return Protocol.listResponse(id, processes, overview);
		});
	}

	private void handleOperate(ChannelHandlerContext ctx, JsonObject req, JsonElement id) {
		ProcessAction action = ProcessAction.fromWire(Protocol.optString(req, "action"));
		if (action == null) {
			Protocol.send(ctx.channel(), Protocol.error("operate", id, "bad_request", "missing or unsupported field: action"));
			return;
		}
		Long pid = Protocol.optLong(req, "pid");
		if (pid == null) {
			Protocol.send(ctx.channel(), Protocol.error("operate", id, "bad_request", "missing or invalid field: pid"));
			return;
		}
		String operator = Protocol.optString(req, "operator");
		if (operator == null || operator.isBlank()) {
			operator = ctx.channel().attr(OPERATOR).get();
		}
		if (operator == null || operator.isBlank()) {
			operator = "unknown@" + ctx.channel().remoteAddress();
		}
		final ProcessAction fAction = action;
		final long fPid = pid;
		final String fOperator = operator;
		submit(ctx, "operate", id, () -> {
			OperationResult r = provider.operate(fAction, fPid, fOperator);
			if (r == null) {
				r = OperationResult.fail("provider returned null");
			}
			return Protocol.result("operate", id, r);
		});
	}

	private interface Job {
		JsonObject run() throws Exception;
	}

	private void submit(ChannelHandlerContext ctx, String type, JsonElement id, Job job) {
		try {
			blockingPool.execute(() -> {
				JsonObject resp;
				try {
					resp = job.run();
				} catch (Exception e) {
					LOGGER.error("handling '{}' failed", type, e);
					resp = Protocol.error(type, id, "internal_error", e.getClass().getSimpleName() + ": " + e.getMessage());
				}
				if (ctx.channel().isActive()) {
					Protocol.send(ctx.channel(), resp);
				}
			});
		} catch (RejectedExecutionException e) {
			Protocol.send(ctx.channel(), Protocol.error(type, id, "server_busy", "server is shutting down or busy"));
		}
	}

	private static boolean isAuthed(ChannelHandlerContext ctx) {
		return Boolean.TRUE.equals(ctx.channel().attr(AUTHED).get());
	}

	private void cancelAuthTimeout() {
		ScheduledFuture<?> t = authTimeoutTask;
		if (t != null) {
			t.cancel(false);
			authTimeoutTask = null;
		}
	}

	private static void closeWith(ChannelHandlerContext ctx, JsonObject msg) {
		if (ctx.channel().isActive()) {
			Protocol.send(ctx.channel(), msg).addListener(ChannelFutureListener.CLOSE);
		} else {
			ctx.close();
		}
	}
}
