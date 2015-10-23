package io.norberg.h2client;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http2.Http2Settings;

class SettingsHandler extends SimpleChannelInboundHandler<Http2Settings> {

  ChannelPromise promise;

  public SettingsHandler(ChannelPromise promise) {
    this.promise = promise;
  }

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, Http2Settings msg) throws Exception {
    promise.setSuccess();

    // Only care about the first settings message
    ctx.pipeline().remove(this);
  }
}