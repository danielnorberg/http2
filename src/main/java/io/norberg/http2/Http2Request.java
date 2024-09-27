package io.norberg.http2;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.AsciiString;

public class Http2Request extends Http2Message<Http2Request> {

  private HttpMethod method;
  private AsciiString scheme;
  private AsciiString authority;
  private AsciiString path;

  Http2Request() {
  }

  Http2Request(final HttpMethod method, final CharSequence path) {
    this(method, path, null);
  }

  Http2Request(final HttpMethod method, final CharSequence path, final ByteBuf content) {
    super(content);
    this.method = method;
    this.path = AsciiString.of(path);
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

  public Http2Response response(final HttpResponseStatus status, final ByteBuf payload) {
    return new Http2Response(status, payload);
  }

  public Http2Response response(final HttpResponseStatus status) {
    return new Http2Response(status);
  }

  @Override
  public String toString() {
    return "Http2Request{" +
        ", headers=" + headersToString() +
        ", content=" + content() +
        '}';
  }

  public static Http2Request of(final HttpMethod method, final CharSequence path) {
    return new Http2Request(method, path);
  }

  public static Http2Request of(final HttpMethod method, final AsciiString path, final ByteBuf payload) {
    return new Http2Request(method, path, payload);
  }
}
