package io.norberg.http2;

import io.netty.buffer.ByteBuf;
import io.netty.util.AsciiString;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

abstract class Http2Message<T extends Http2Message<T>> {

  private AsciiString[] headers;
  private int headerIx;
  private ByteBuf content;

  Http2Message() {
  }

  Http2Message(ByteBuf content) {
    this.content = content;
  }

  public T content(final ByteBuf content) {
    this.content = content;
    return self();
  }

  public boolean hasContent() {
    return content != null;
  }

  public ByteBuf content() {
    return content;
  }

  public boolean hasHeaders() {
    return headers != null;
  }

  public int numHeaders() {
    return headerIx >> 1;
  }

  public AsciiString headerName(int i) {
    if (headers == null) {
      throw new IndexOutOfBoundsException("Index: " + i + ", Size: " + 0);
    }
    final int ix = i << 1;
    return headers[ix];
  }

  public AsciiString headerValue(int i) {
    if (headers == null) {
      throw new IndexOutOfBoundsException("Index: " + i + ", Size: " + 0);
    }
    final int ix = i << 1;
    return headers[ix + 1];
  }

  public T header(final AsciiString name, final AsciiString value) {
    if (headers == null) {
      headers = new AsciiString[16];
    }
    if (headerIx >= headers.length) {
      headers = Arrays.copyOf(headers, headers.length * 2);
    }
    headers[headerIx] = name;
    headers[headerIx + 1] = value;
    headerIx += 2;
    return self();
  }

  public T headers(Iterable<Entry<AsciiString, AsciiString>> headers) {
    headers.forEach(e -> header(e.getKey(), e.getValue()));
    return self();
  }

  public T headers(Stream<Entry<AsciiString, AsciiString>> headers) {
    headers.forEach(e -> header(e.getKey(), e.getValue()));
    return self();
  }

  public Stream<Entry<AsciiString, AsciiString>> headers() {
    return IntStream.range(0, numHeaders())
        .mapToObj(i -> new AbstractMap.SimpleImmutableEntry<>(headerName(i), headerValue(i)));
  }

  String headersToString() {
    final int n = numHeaders();
    if (n == 0) {
      return "{}";
    }
    final StringBuilder s = new StringBuilder().append('{');
    int i = 0;
    while (true) {
      s.append(headerName(i)).append('=').append(headerValue(i));
      i++;
      if (i >= n) {
        s.append('}');
        return s.toString();
      }
      s.append(',').append(' ');
    }
  }

  void releaseHeaders() {
    if (headers != null) {
      Arrays.fill(headers, null);
      headers = null;
    }
  }

  public void forEachHeader(BiConsumer<AsciiString, AsciiString> action) {
    Objects.requireNonNull(action);
    for (int i = 0; i < numHeaders(); i++) {
      action.accept(headerName(i), headerValue(i));
    }
  }

  public void release() {
    releaseHeaders();
    if (hasContent()) {
      content.release();
    }
  }

  @SuppressWarnings("unchecked")
  private T self() {
    return (T) this;
  }

  void addContent(ByteBuf data) {
    this.content = Util.appendBytes(content, data);
  }

}
