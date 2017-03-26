package io.norberg.h2client;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Flags;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Settings;

interface Http2FrameListener {

  void onPushPromiseHeadersEnd(ChannelHandlerContext ctx, int streamId);

  int onDataRead(ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding,
      boolean endOfStream) throws Http2Exception;

  void onHeadersRead(ChannelHandlerContext ctx, int streamId, boolean endOfStream)
      throws Http2Exception;

  void onHeadersRead(ChannelHandlerContext ctx, int streamId, int streamDependency, short weight,
      boolean exclusive, boolean endOfStream)
      throws Http2Exception;

  void onHeaderRead(Http2Header header) throws Http2Exception;

  void onHeadersEnd(ChannelHandlerContext ctx, int streamId, final boolean endOfStream) throws Http2Exception;

  void onPriorityRead(ChannelHandlerContext ctx, int streamId, int streamDependency,
      short weight, boolean exclusive) throws Http2Exception;

  void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode) throws Http2Exception;

  void onSettingsAckRead(ChannelHandlerContext ctx) throws Http2Exception;

  void onSettingsRead(ChannelHandlerContext ctx, Http2Settings settings) throws Http2Exception;

  void onPingRead(ChannelHandlerContext ctx, ByteBuf data) throws Http2Exception;

  void onPingAckRead(ChannelHandlerContext ctx, ByteBuf data) throws Http2Exception;

  void onPushPromiseRead(ChannelHandlerContext ctx, int streamId, int promisedStreamId,
      Http2Headers headers, int padding) throws Http2Exception;

  void onGoAwayRead(ChannelHandlerContext ctx, int lastStreamId, long errorCode, ByteBuf debugData)
      throws Http2Exception;

  void onWindowUpdateRead(ChannelHandlerContext ctx, int streamId, int windowSizeIncrement)
      throws Http2Exception;

  void onUnknownFrame(ChannelHandlerContext ctx, byte frameType, int streamId, Http2Flags flags, ByteBuf payload)
      throws Http2Exception;
}
