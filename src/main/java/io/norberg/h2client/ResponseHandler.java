package io.norberg.h2client;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http2.HttpConversionUtil;

class ResponseHandler extends SimpleChannelInboundHandler<FullHttpResponse> {

  private Map<Integer, CompletableFuture<FullHttpResponse>> outstanding;

  public ResponseHandler() {
    super(false);
    // TODO: only touch outstanding map on io thread and use HashMap instead
    outstanding = new ConcurrentHashMap<>();
  }

  public void expect(int streamId, CompletableFuture<FullHttpResponse> future) {
    outstanding.put(streamId, future);
  }

  public void cancel(final int streamId) {
    outstanding.remove(streamId);
  }

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse msg) throws Exception {
    final Integer streamId = msg.headers().getInt(HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text());
    if (streamId == null) {
      System.err.println("Received unexpected message: " + msg);
      return;
    }

    final CompletableFuture<FullHttpResponse> future = outstanding.remove(streamId);
    if (future == null) {
      System.err.println("Received unexpected message with stream id: " + streamId);
      return;
    }

    future.complete(msg);
  }
}