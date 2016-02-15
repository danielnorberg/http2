package io.norberg.h2client;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2Exception;

interface Http2FrameListener extends io.netty.handler.codec.http2.Http2FrameListener {

  void onHeaderRead(Http2Header header) throws Http2Exception;

  void onHeadersEnd(ChannelHandlerContext ctx, int streamId, final boolean endOfStream) throws Http2Exception;

  void onPushPromiseHeadersEnd(ChannelHandlerContext ctx, int streamId);
}
