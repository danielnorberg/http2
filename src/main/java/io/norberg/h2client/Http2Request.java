package io.norberg.h2client;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.util.AsciiString;

public class Http2Request {

  private int streamId;
  private Http2Headers headers;
  private ByteBuf content;

  Http2Request(final int streamId) {
    this.streamId = streamId;
  }

  public Http2Request(final HttpMethod method, final String path) {
    this.headers = new DefaultHttp2Headers();
    this.headers.method(method.asciiName());
    this.headers.path(AsciiString.of(path));
  }

  public Http2Request(final HttpMethod method, final String path, final ByteBuf content) {
    this.headers = new DefaultHttp2Headers();
    this.headers.method(method.asciiName());
    this.headers.path(AsciiString.of(path));
    this.content = content;
  }

  public int streamId() {
    return streamId;
  }

  void streamId(final int streamId) {
    this.streamId = streamId;
  }

  public Http2Headers headers() {
    return headers;
  }

  void headers(final Http2Headers headers) {
    this.headers = headers;
  }

  public boolean hasContent() {
    return content != null;
  }

  public ByteBuf content() {
    return content;
  }

  public void content(final ByteBuf content) {
    this.content = content;
  }

  public Http2Response response(final HttpResponseStatus status, final ByteBuf payload) {
    return new Http2Response(streamId, status, payload);
  }

  public Http2Response response(final HttpResponseStatus status) {
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
