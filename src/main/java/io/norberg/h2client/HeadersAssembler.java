package io.norberg.h2client;

import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Headers;

class HeadersAssembler implements HpackDecoder.Listener {

  private Http2Headers headers;

  void reset() {
    headers = new DefaultHttp2Headers();
  }

  @Override
  public void header(final Http2Header header) {
    headers().add(header.name(), header.value());
  }

  Http2Headers headers() {
    return headers;
  }
}
