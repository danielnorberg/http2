package io.norberg.http2;

import io.netty.handler.codec.http2.Http2Exception;

import static io.netty.handler.codec.http2.Http2Error.COMPRESSION_ERROR;

public class HpackEncodingException extends Http2Exception {

  private static final long serialVersionUID = 7404139363847910491L;

  public HpackEncodingException() {
    super(COMPRESSION_ERROR);
  }
}
