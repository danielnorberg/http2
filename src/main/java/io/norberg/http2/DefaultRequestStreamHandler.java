package io.norberg.http2;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.util.AsciiString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultRequestStreamHandler implements RequestStreamHandler {

  private static final Logger log = LoggerFactory.getLogger(DefaultRequestStreamHandler.class);

  private final RequestHandler requestHandler;
  private final Http2RequestContext stream;

  private Http2Request request = new Http2Request();

  public DefaultRequestStreamHandler(RequestHandler requestHandler, Http2RequestContext stream) {
    this.requestHandler = requestHandler;
    this.stream = stream;
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
}
