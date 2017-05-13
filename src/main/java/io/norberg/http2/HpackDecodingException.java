package io.norberg.http2;

import static io.netty.handler.codec.http2.Http2Error.COMPRESSION_ERROR;

import io.netty.handler.codec.http2.Http2Exception;

class HpackDecodingException extends Http2Exception {

  private static final long serialVersionUID = 5331479808332045320L;

  public HpackDecodingException() {
    super(COMPRESSION_ERROR);
  }
}
