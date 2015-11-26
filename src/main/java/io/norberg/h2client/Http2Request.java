package io.norberg.h2client;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.util.AsciiString;

public class Http2Request {

  private HttpMethod method;
  private AsciiString scheme;
  private AsciiString authority;
  private AsciiString path;
  private int streamId;
  private Http2Headers headers;
  private ByteBuf content;

  Http2Request(final int streamId) {
    this.streamId = streamId;
  }

  public Http2Request(final HttpMethod method, final CharSequence path) {
    this(method, path, null);
  }

  public Http2Request(final HttpMethod method, final CharSequence path, final ByteBuf content) {
    this.method = method;
    this.path = AsciiString.of(path);
    this.content = content;
  }

  public HttpMethod method() {
    return method;
  }

  public void method(final HttpMethod method) {
    this.method = method;
  }

  public AsciiString scheme() {
    return scheme;
  }

  public void scheme(final AsciiString scheme) {
    this.scheme = scheme;
  }

  public AsciiString authority() {
    return authority;
  }

  public void authority(final AsciiString authority) {
    this.authority = authority;
  }

  public AsciiString path() {
    return path;
  }

  public void path(final AsciiString path) {
    this.path = path;
  }

  public int streamId() {
    return streamId;
  }

  void streamId(final int streamId) {
    this.streamId = streamId;
  }

  public boolean hasHeaders() {
    return headers != null;
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

  public void header(final AsciiString name, final AsciiString value) {
    // TODO: cheaper header list data structure
    if (headers == null) {
      headers = new DefaultHttp2Headers(false);
    }
    headers.add(name, value);
  }
}
