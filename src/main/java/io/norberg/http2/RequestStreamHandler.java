package io.norberg.http2;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.util.AsciiString;

public interface RequestStreamHandler {

  RequestStreamHandler NOP = new RequestStreamHandlerAdapter();

  void headers(Http2Request request);

  void data(ByteBuf data);

  void trailers(Http2Headers trailers);

  void end();

  void reset(Http2Error error);
}
