package com.taskmanager.remote;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import java.util.List;

/** 出站方向给每条 String 消息追加换行。放在 StringEncoder 之后。 */
@ChannelHandler.Sharable
public final class NewlineAppender extends MessageToMessageEncoder<String> {
	public static final NewlineAppender INSTANCE = new NewlineAppender();

	@Override
	protected void encode(ChannelHandlerContext ctx, String msg, List<Object> out) {
		out.add(msg.endsWith("\n") ? msg : msg + "\n");
	}
}
