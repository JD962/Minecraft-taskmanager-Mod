package com.taskmanager.remote;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.ChannelMatcher;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.concurrent.GlobalEventExecutor;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 任务管理器远程管理 TCP 服务端，JSON 行协议。
 * 默认绑定 127.0.0.1；仅 token 认证，无 TLS，对外监听需配合防火墙。
 */
public final class TaskManagerServer {
	private static final Logger LOGGER = LoggerFactory.getLogger("TaskManager/Server");

	private final TaskManagerServerConfig config;
	private final ProcessDataProvider provider;
	private final Object lock = new Object();
	private final ChannelGroup clients = new DefaultChannelGroup("tm-clients", GlobalEventExecutor.INSTANCE);
	private final TrafficCounter traffic = TrafficCounter.getInstance();

	private static final ChannelMatcher AUTHED_ONLY =
		ch -> Boolean.TRUE.equals(ch.attr(ServerHandler.AUTHED).get()) && ch.isWritable();

	private EventLoopGroup bossGroup;
	private EventLoopGroup workerGroup;
	private ExecutorService blockingPool;
	private Channel serverChannel;
	private volatile boolean running;

	public TaskManagerServer(TaskManagerServerConfig config, ProcessDataProvider provider) {
		this.config = Objects.requireNonNull(config, "config");
		this.provider = Objects.requireNonNull(provider, "provider");
	}

	public boolean isRunning() {
		return running;
	}

	public int connectionCount() {
		return clients.size();
	}

	/** 累计接收字节数（网络下行，扩展指标）。 */
	public long trafficInBytes() {
		return traffic.bytesIn();
	}

	/** 累计发送字节数（网络上行，扩展指标）。 */
	public long trafficOutBytes() {
		return traffic.bytesOut();
	}

	// netty 4.1.97 将 NioEventLoopGroup 标记为 deprecated（为 4.2 的 MultiThreadIoEventLoopGroup 预热），
	// 但替代类尚未在 4.1.x 引入，故保留并抑制警告。
	@SuppressWarnings("deprecation")
	public void start() throws InterruptedException {
		synchronized (lock) {
			if (running) {
				return;
			}
			bossGroup = new NioEventLoopGroup(1, daemonFactory("tm-boss"));
			workerGroup = new NioEventLoopGroup(2, daemonFactory("tm-worker"));
			// 有界任务队列，防止已认证客户端灌请求导致无界堆积
			blockingPool = new ThreadPoolExecutor(
				config.blockingThreads(), config.blockingThreads(),
				0L, TimeUnit.MILLISECONDS,
				new ArrayBlockingQueue<>(256),
				daemonFactory("tm-task"),
				new ThreadPoolExecutor.AbortPolicy());
			try {
				ServerBootstrap b = new ServerBootstrap()
					.group(bossGroup, workerGroup)
					.channel(NioServerSocketChannel.class)
					.option(ChannelOption.SO_BACKLOG, 64)
					.option(ChannelOption.SO_REUSEADDR, true)
					.childOption(ChannelOption.TCP_NODELAY, true)
					.childOption(ChannelOption.SO_KEEPALIVE, true)
					.childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
						new WriteBufferWaterMark(32 * 1024, 128 * 1024))
					.childHandler(new ChannelInitializer<SocketChannel>() {
						@Override
						protected void initChannel(SocketChannel ch) {
							ChannelPipeline p = ch.pipeline();
							p.addLast("framer", new LineBasedFrameDecoder(config.maxLineLength(), true, true));
							p.addLast("decoder", new StringDecoder(StandardCharsets.UTF_8));
							p.addLast("encoder", new StringEncoder(StandardCharsets.UTF_8));
							p.addLast("newline", NewlineAppender.INSTANCE);
							p.addLast("traffic", traffic);
							p.addLast("handler", new ServerHandler(config, provider, blockingPool, clients));
						}
					});
				ChannelFuture f = b.bind(new InetSocketAddress(config.bindHost(), config.port())).sync();
				serverChannel = f.channel();
				running = true;
				LOGGER.info("task manager server listening on {}:{}", config.bindHost(), config.port());
			} catch (InterruptedException e) {
				cleanupAfterFailedStart();
				Thread.currentThread().interrupt();
				throw e;
			} catch (Exception e) {
				cleanupAfterFailedStart();
				throw new IllegalStateException("failed to bind " + config.bindHost() + ":" + config.port(), e);
			}
		}
	}

	private void cleanupAfterFailedStart() {
		if (blockingPool != null) {
			blockingPool.shutdownNow();
			blockingPool = null;
		}
		if (workerGroup != null) {
			workerGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS);
			workerGroup = null;
		}
		if (bossGroup != null) {
			bossGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS);
			bossGroup = null;
		}
		serverChannel = null;
		running = false;
	}

	public void stop() {
		stop(5, TimeUnit.SECONDS);
	}

	public void stop(long timeout, TimeUnit unit) {
		synchronized (lock) {
			if (!running && serverChannel == null && bossGroup == null) {
				return;
			}
			running = false;
			long deadlineMs = Math.max(100L, unit.toMillis(timeout));
			// shutdownGracefully 要求 timeout >= quietPeriod，夹紧参数避免 IllegalArgumentException
			long quiet = Math.min(100L, deadlineMs);
			try {
				if (serverChannel != null) {
					serverChannel.close().await(deadlineMs, TimeUnit.MILLISECONDS);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} finally {
				serverChannel = null;
			}
			try {
				clients.close().await(deadlineMs, TimeUnit.MILLISECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			clients.clear();
			if (blockingPool != null) {
				blockingPool.shutdown();
				try {
					if (!blockingPool.awaitTermination(deadlineMs, TimeUnit.MILLISECONDS)) {
						blockingPool.shutdownNow();
					}
				} catch (InterruptedException e) {
					blockingPool.shutdownNow();
					Thread.currentThread().interrupt();
				}
				blockingPool = null;
			}
			io.netty.util.concurrent.Future<?> wf = workerGroup == null ? null
				: workerGroup.shutdownGracefully(quiet, deadlineMs, TimeUnit.MILLISECONDS);
			io.netty.util.concurrent.Future<?> bf = bossGroup == null ? null
				: bossGroup.shutdownGracefully(quiet, deadlineMs, TimeUnit.MILLISECONDS);
			try {
				if (wf != null) wf.await(deadlineMs, TimeUnit.MILLISECONDS);
				if (bf != null) bf.await(deadlineMs, TimeUnit.MILLISECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			workerGroup = null;
			bossGroup = null;
			LOGGER.info("task manager server stopped");
		}
	}

	public void broadcast(JsonObject msg) {
		if (msg == null || clients.isEmpty()) {
			return;
		}
		clients.writeAndFlush(Protocol.toLine(msg), AUTHED_ONLY);
	}

	public void broadcast(String type, JsonElement data) {
		JsonObject o = new JsonObject();
		o.addProperty("type", type);
		o.addProperty("success", true);
		if (data != null) {
			o.add("data", data);
		}
		broadcast(o);
	}

	public void broadcastProcessList(List<ProcessInfo> processes) {
		broadcast("list_push", Protocol.toJsonArray(processes));
	}

	public void send(Channel channel, JsonObject msg) {
		if (channel != null && channel.isActive() && msg != null) {
			Protocol.send(channel, msg);
		}
	}

	private static ThreadFactory daemonFactory(String prefix) {
		AtomicInteger seq = new AtomicInteger();
		return r -> {
			Thread t = new Thread(r, prefix + "-" + seq.incrementAndGet());
			t.setDaemon(true);
			return t;
		};
	}
}
