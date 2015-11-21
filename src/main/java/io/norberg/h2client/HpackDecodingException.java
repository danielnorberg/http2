package io.norberg.h2client;

import io.netty.handler.codec.http2.Http2Exception;

import static io.netty.handler.codec.http2.Http2Error.COMPRESSION_ERROR;

class HpackDecodingException extends Http2Exception {

  public HpackDecodingException() {
    super(COMPRESSION_ERROR);
  }
}
