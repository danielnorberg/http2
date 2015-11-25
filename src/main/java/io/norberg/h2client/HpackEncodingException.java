package io.norberg.h2client;

import io.netty.handler.codec.http2.Http2Exception;

import static io.netty.handler.codec.http2.Http2Error.COMPRESSION_ERROR;

public class HpackEncodingException extends Http2Exception {

  public HpackEncodingException() {
    super(COMPRESSION_ERROR);
  }
}
