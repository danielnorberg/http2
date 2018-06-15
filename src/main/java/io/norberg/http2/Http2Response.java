package io.norberg.http2;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpResponseStatus;

public class Http2Response extends Http2Message<Http2Response> {

  private HttpResponseStatus status;

  private boolean end = true;

  public Http2Response() {
  }

  public Http2Response(final HttpResponseStatus status) {
    this(status, null);
  }

  public Http2Response(final HttpResponseStatus status, final ByteBuf content) {
    super(content);
    this.status = status;
  }

  public void status(final HttpResponseStatus status) {
    this.status = status;
  }

  public HttpResponseStatus status() {
    return status;
  }

  public boolean end() {
    return end;
  }

  public Http2Response end(boolean end) {
    this.end = end;
    return this;
  }

  @Override
  public String toString() {
    return "Http2Response{" +
        ", content=" + content() +
        ", headers=" + headersToString() +
        ", end=" + end +
        '}';
  }
}
