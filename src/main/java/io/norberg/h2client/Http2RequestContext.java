package io.norberg.h2client;

import io.netty.channel.ChannelHandlerContext;

import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;

public class Http2RequestContext {

  private final Http2Server.FrameHandler handler;
  private final ChannelHandlerContext ctx;
  private final int streamId;

  Http2RequestContext(final Http2Server.FrameHandler handler, final ChannelHandlerContext ctx, final int streamId) {
    this.handler = handler;
    this.ctx = ctx;
    this.streamId = streamId;
  }

  public void respond(final Http2Response response) {
    handler.sendResponse(ctx, response, streamId);
  }

  public void fail() {
    // Return 500 for request handler errors
    respond(new Http2Response(INTERNAL_SERVER_ERROR));
  }
}
