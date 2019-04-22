package io.norberg.http2;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.util.AsciiString;

public class RequestStreamHandlerAdapter implements RequestStreamHandler {

  @Override
  public void method(HttpMethod method) {
  }

  @Override
  public void scheme(AsciiString scheme) {
  }

  @Override
  public void authority(AsciiString authority) {
  }

  @Override
  public void path(AsciiString path) {
  }

  @Override
  public void header(AsciiString name, AsciiString value) {
  }

  @Override
  public void startHeaders() {
  }

  @Override
  public void endHeaders() {
  }

  @Override
  public void data(ByteBuf data) {
  }

  @Override
  public void startTrailers() {
  }

  @Override
  public void trailer(AsciiString name, AsciiString value) {
  }

  @Override
  public void endTrailers() {
  }

  @Override
  public void end() {
  }

  @Override
  public void reset(Http2Error error) {
  }
}
