package io.norberg.http2;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.util.AsciiString;

public interface RequestStreamHandler {

  RequestStreamHandler NOP = new RequestStreamHandlerAdapter();

  void method(HttpMethod method);

  void scheme(AsciiString scheme);

  void authority(AsciiString authority);

  void path(AsciiString path);

  void header(AsciiString name, AsciiString value);

  void startHeaders();

  void endHeaders();

  void data(ByteBuf data);

  void startTrailers();

  void trailer(AsciiString name, AsciiString value);

  void endTrailers();

  void end();

  void reset(Http2Error error);
}
