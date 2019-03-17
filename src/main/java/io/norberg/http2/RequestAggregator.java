package io.norberg.http2;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.util.AsciiString;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class RequestAggregator implements RequestStreamHandler {

  private static final Logger log = LoggerFactory.getLogger(RequestAggregator.class);

  private final FullRequestHandler requestHandler;
  private final Http2RequestContext stream;

  private Http2Request request = new Http2Request();

  private RequestAggregator(FullRequestHandler requestHandler, Http2RequestContext stream) {
    this.requestHandler = Objects.requireNonNull(requestHandler, "requestHandler");
    this.stream = Objects.requireNonNull(stream, "stream");
  }

  @Override
  public void data(ByteBuf data, int padding) {
    // TODO: allow user to provide codec that can be used to parse payload directly without copying it
    final ByteBuf content = request.content();
    if (content == null) {
      request.content(Unpooled.copiedBuffer(data));
    } else {
      content.writeBytes(data);
    }
  }

  @Override
  public void end() {
    // Hand off request to request handler
    try {
      requestHandler.handleRequest(stream, request);
    } catch (Exception e) {
      log.error("Request handler threw exception", e);
      stream.fail();
    }
  }

  @Override
  public void header(AsciiString name, AsciiString value) {
    request.header(name, value);
  }

  @Override
  public void method(HttpMethod method) {
    request.method(method);
  }

  @Override
  public void scheme(AsciiString scheme) {
    request.scheme(scheme);
  }

  @Override
  public void authority(AsciiString authority) {
    request.authority(authority);
  }

  @Override
  public void path(AsciiString path) {
    request.path(path);
  }

  static RequestStreamHandler of(FullRequestHandler handler, Http2RequestContext stream) {
    return new RequestAggregator(handler, stream);
  }
}
