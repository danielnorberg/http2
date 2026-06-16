package io.norberg.http2;

import io.netty.buffer.ByteBuf;

public interface Http2RequestContext {

  void send(Http2Response response);

  void fail();

  void data(ByteBuf data);

  void end();

  default void end(Http2Response response) {
    send(response.end(true));
  }
}
