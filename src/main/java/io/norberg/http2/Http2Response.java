package io.norberg.http2;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpResponseStatus;

public class Http2Response extends Http2Message<Http2Response> {

  private HttpResponseStatus status;
  private ByteBuf content;

  public Http2Response() {
  }

  public Http2Response(final HttpResponseStatus status) {
    this(status, null);
  }

  public Http2Response(final HttpResponseStatus status, final ByteBuf content) {
    this.content = content;
    this.status = status;
  }

  public void status(final HttpResponseStatus status) {
    this.status = status;
  }

  public HttpResponseStatus status() {
    return status;
  }

  public Http2Response content(final ByteBuf content) {
    this.content = content;
    return this;
  }

  public boolean hasContent() {
    return content != null;
  }

  public ByteBuf content() {
    return content;
  }

  public void release() {
    releaseHeaders();
    if (hasContent()) {
      content.release();
    }
  }

  @Override
  public String toString() {
    return "Http2Response{" +
           ", content=" + content +
           ", headers=" + headersToString() +
           '}';
  }
}
