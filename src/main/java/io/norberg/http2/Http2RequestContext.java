package io.norberg.http2;

import io.netty.buffer.ByteBuf;

public interface Http2RequestContext {

  void respond(final Http2Response response);

  void fail();

  void send(ByteBuf data);

  void end();
}
