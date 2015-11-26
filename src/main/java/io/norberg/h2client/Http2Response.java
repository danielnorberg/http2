package io.norberg.h2client;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.util.AsciiString;

public class Http2Response {

  private HttpResponseStatus status;
  private int streamId;
  private ByteBuf content;
  private Http2Headers headers;

  public Http2Response() {
  }

  public Http2Response(final int streamId, final HttpResponseStatus status) {
    this(streamId, status, null);
  }

  public Http2Response(final int streamId, final HttpResponseStatus status, final ByteBuf content) {
    this.streamId = streamId;
    this.content = content;
    this.status = status;
  }

  public void status(final HttpResponseStatus status) {
    this.status = status;
  }

  public HttpResponseStatus status() {
    return status;
  }

  void headers(final Http2Headers headers) {
    this.headers = headers;
  }

  void content(final ByteBuf content) {
    this.content = content;
  }

  public int streamId() {
    return streamId;
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

  @Override
  public String toString() {
    return "Http2Response{" +
           "streamId=" + streamId +
           ", content=" + content +
           ", headers=" + headers +
           '}';
  }

  public void release() {
    if (hasContent()) {
      content.release();
    }
  }

  public void header(final AsciiString name, final AsciiString value) {
    // TODO: cheaper header list data structure
    if (headers == null) {
      headers = new DefaultHttp2Headers(false);
    }
    headers.add(name, value);
  }
}
