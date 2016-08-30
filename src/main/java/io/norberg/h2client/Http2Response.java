package io.norberg.h2client;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.util.AsciiString;

public class Http2Response {

  private HttpResponseStatus status;
  private ByteBuf content;
  private Http2Headers headers;

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

  Http2Response headers(final Http2Headers headers) {
    this.headers = headers;
    return this;
  }

  Http2Response content(final ByteBuf content) {
    this.content = content;
    return this;
  }

  public boolean hasHeaders() {
    return headers != null;
  }

  public Http2Headers headers() {
    return headers;
  }

  public boolean hasContent() {
    return content != null;
  }

  public ByteBuf content() {
    return content;
  }

  public void release() {
    if (hasContent()) {
      content.release();
    }
  }

  @Override
  public String toString() {
    return "Http2Response{" +
           ", content=" + content +
           ", headers=" + headers +
           '}';
  }

  public void header(final AsciiString name, final AsciiString value) {
    // TODO: cheaper header list data structure
    if (headers == null) {
      headers = new DefaultHttp2Headers(true);
    }
    headers.add(name, value);
  }

  public void header(final CharSequence name, final CharSequence value) {
    header(AsciiString.of(name), AsciiString.of(value));
  }
}
