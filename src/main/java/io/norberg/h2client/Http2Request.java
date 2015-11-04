package io.norberg.h2client;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http2.Http2Headers;

public class Http2Request {

  private int streamId;
  private Http2Headers headers;
  private ByteBuf content;

  public Http2Request(final int streamId) {
    this.streamId = streamId;
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

  public Http2Response response(final int status, final ByteBuf payload) {
    return new Http2Response(streamId, status, payload);
  }

  public Http2Response response(final int status) {
    return new Http2Response(streamId, status);
  }

  @Override
  public String toString() {
    return "Http2Request{" +
           "streamId=" + streamId +
           ", headers=" + headers +
           ", content=" + content +
           '}';
  }
}
