package io.norberg.http2;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpResponseStatus;

public class Http2Response extends Http2Message<Http2Response> {

  private HttpResponseStatus status;

  public Http2Response() {
  }

  public Http2Response(final HttpResponseStatus status) {
    this(status, null);
  }

  public Http2Response(final HttpResponseStatus status, final ByteBuf content) {
    super(content);
    this.status = status;
  }

  public Http2Response status(final HttpResponseStatus status) {
    this.status = status;
    return this;
  }

  public HttpResponseStatus status() {
    return status;
  }

  @Override
  public String toString() {
    return "Http2Response{" +
           ", status=" + status +
           ", content=" + content() +
           ", headers=" + headersToString() +
           '}';
  }

  public static Http2Response of() {
    return new Http2Response();
  }

  public static Http2Response of(HttpResponseStatus status) {
    return new Http2Response(status);
  }
}
