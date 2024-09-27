package io.norberg.http2;

import io.netty.buffer.ByteBuf;

public interface Http2ResponseWriter {

  void send(Http2Response end);

  void fail();

  void data(ByteBuf data);

  void end();

  default void end(Http2Response response) {
    send(response.end(true));
  }

  default void end(ByteBuf data, Http2Headers trailers) {
    send(Http2Response.of()
        .content(data)
        .trailers(trailers));
  }
}
