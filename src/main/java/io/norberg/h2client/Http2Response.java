package io.norberg.h2client;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Headers;

public class Http2Response {

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
    this.headers = new DefaultHttp2Headers();
    headers.status(status.codeAsText());
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
}
