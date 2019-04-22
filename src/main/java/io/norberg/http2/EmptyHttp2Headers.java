package io.norberg.http2;

import io.netty.util.AsciiString;
import java.util.function.BiConsumer;

class EmptyHttp2Headers implements Http2Headers {

  @Override
  public int size() {
    return 0;
  }

  @Override
  public boolean isEmpty() {
    return true;
  }

  @Override
  public AsciiString name(int i) {
    throw new IndexOutOfBoundsException("Index: " + i + ", Size: " + 0);
  }

  @Override
  public AsciiString value(int i) {
    throw new IndexOutOfBoundsException("Index: " + i + ", Size: " + 0);
  }

  @Override
  public Http2Headers add(AsciiString name, AsciiString value) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Http2Headers add(String name, String value) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Http2Headers add(Http2Header header) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void forEachHeader(BiConsumer<AsciiString, AsciiString> action) {
  }
}
