package io.norberg.http2;

import io.netty.buffer.ByteBuf;

public class RequestStreamHandlerAdapter implements RequestStreamHandler {

  @Override
  public void headers(Http2Request request) {
  }

  @Override
  public void data(ByteBuf data) {
  }

  @Override
  public void trailers(Http2Headers trailers) {
  }

  @Override
  public void end() {
  }

  @Override
  public void reset(Http2Error error) {
  }
}
