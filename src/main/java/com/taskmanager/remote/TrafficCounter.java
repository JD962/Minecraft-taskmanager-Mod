package com.taskmanager.remote;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 网络流量统计 handler：统计经过管道的收发字节数（含协议头/行分隔符的原始字节）。
 * 用于概览面板展示「网络 上行/下行」扩展指标。
 */
public final class TrafficCounter extends ChannelDuplexHandler {
	private static final TrafficCounter INSTANCE = new TrafficCounter();

	private final AtomicLong bytesIn = new AtomicLong();
	private final AtomicLong bytesOut = new AtomicLong();

	private TrafficCounter() {
	}

	public static TrafficCounter getInstance() {
		return INSTANCE;
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		if (msg instanceof ByteBuf buf) {
			bytesIn.addAndGet(buf.readableBytes());
		}
		super.channelRead(ctx, msg);
	}

	@Override
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
		if (msg instanceof ByteBuf buf) {
			bytesOut.addAndGet(buf.readableBytes());
		}
		super.write(ctx, msg, promise);
	}

	/** 累计接收字节数。 */
	public long bytesIn() {
		return bytesIn.get();
	}

	/** 累计发送字节数。 */
	public long bytesOut() {
		return bytesOut.get();
	}
}
