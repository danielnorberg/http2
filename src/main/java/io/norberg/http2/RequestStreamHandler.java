package io.norberg.http2;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.util.AsciiString;
import java.util.function.Function;

public interface RequestStreamHandler {

  void method(HttpMethod method);

  void scheme(AsciiString scheme);

  void authority(AsciiString authority);

  void path(AsciiString path);

  void header(AsciiString name, AsciiString value);

  void data(ByteBuf data, int padding);

  void end();

  @FunctionalInterface
  interface Factory extends Function<Http2RequestContext, RequestStreamHandler> {}
}
