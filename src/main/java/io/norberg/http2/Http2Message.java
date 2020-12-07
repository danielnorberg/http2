package io.norberg.http2;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.util.AsciiString;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

abstract class Http2Message<T extends Http2Message<T>> {

  private AsciiString[] headers;
  private int headerIx;
  private int trailingHeaders = 0;
  private ByteBuf content;

  Http2Message() {
  }

  Http2Message(ByteBuf content) {
    this.content = content;
  }

  public boolean hasHeaders() {
    return headers != null;
  }

  boolean hasInitialHeaders() {
    return numInitialHeaders() != 0;
  }

  public boolean hasTrailingHeaders() {
    return trailingHeaders != 0;
  }

  public int numHeaders() {
    return headerIx >> 1;
  }

  public int numInitialHeaders() {
    return numHeaders() - numTrailingHeaders();
  }

  public int numTrailingHeaders() {
    return trailingHeaders;
  }

  public int trailingHeaderIndex() {
    return numInitialHeaders();
  }

  public boolean headerIsTrailing(int i) {
    return i >= numInitialHeaders();
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

  public Stream<Map.Entry<AsciiString, AsciiString>> headers() {
    return IntStream.range(0, numHeaders())
        .mapToObj(i -> new AbstractMap.SimpleImmutableEntry<>(headerName(i), headerValue(i)));
  }

  public void forEachHeader(BiConsumer<AsciiString, AsciiString> action) {
    Objects.requireNonNull(action);
    for (int i = 0; i < numHeaders(); i++) {
      action.accept(headerName(i), headerValue(i));
    }
  }

  public T header(final String name, final String value) {
    return header(AsciiString.of(name), AsciiString.of(value));
  }

  public T header(final AsciiString name, final AsciiString value) {
    return initialHeader(name, value);
  }

  public T headers(Iterable<Map.Entry<AsciiString, AsciiString>> headers) {
    headers.forEach(e -> header(e.getKey(), e.getValue()));
    return self();
  }

  public T headers(Stream<Map.Entry<AsciiString, AsciiString>> headers) {
    headers.forEach(e -> header(e.getKey(), e.getValue()));
    return self();
  }

  public T headers(Http2Message<?> message) {
    initialHeaders(message);
    trailingHeaders(message);
    return self();
  }

  public T headers(Http2Headers headers) {
    for (int i = 0; i < headers.size(); i++) {
      header(headers.name(i), headers.value(i));
    }
    return self();
  }

  public T initialHeader(String name, String value) {
    return initialHeader(AsciiString.of(name), AsciiString.of(value));
  }

  public T initialHeader(AsciiString name, AsciiString value) {
    if (trailingHeaders != 0) {
      throw new IllegalStateException("Cannot add initial headers after trailing headers");
    }
    return header0(name, value);
  }

  public T initialHeaders(Http2Message<?> message) {
    for (int i = 0; i < message.numInitialHeaders(); i++) {
      header(message.headerName(i), message.headerValue(i));
    }
    return self();
  }

  public T trailingHeader(String name, String value) {
    return trailingHeader(AsciiString.of(name), AsciiString.of(value));
  }

  public T trailingHeader(AsciiString name, AsciiString value) {
    ++trailingHeaders;
    return header0(name, value);
  }

  public T trailingHeaders(Http2Headers headers) {
    headers.forEachHeader(this::trailingHeader);
    return self();
  }

  public T trailingHeaders(Iterable<Map.Entry<AsciiString, AsciiString>> headers) {
    headers.forEach(e -> trailingHeader(e.getKey(), e.getValue()));
    return self();
  }

  public T trailingHeaders(Stream<Map.Entry<AsciiString, AsciiString>> headers) {
    headers.forEach(e -> trailingHeader(e.getKey(), e.getValue()));
    return self();
  }

  public T trailingHeaders(Http2Message<?> message) {
    for (int i = message.numInitialHeaders(); i < message.numHeaders(); i++) {
      trailingHeader(message.headerName(i), message.headerValue(i));
    }
    return self();
  }

  private T header0(AsciiString name, AsciiString value) {
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

  public T content(final ByteBuf content) {
    this.content = content;
    return self();
  }

  public T addContent(final ByteBuf data) {
    this.content = Util.appendBytes(content, data);
    return self();
  }

  public T contentUtf8(final String content) {
    return content(ByteBufUtil.writeUtf8(UnpooledByteBufAllocator.DEFAULT, content));
  }

  public T addContentUtf8(final String content) {
    return addContent(ByteBufUtil.writeUtf8(UnpooledByteBufAllocator.DEFAULT, content));
  }

  public boolean hasContent() {
    return content != null;
  }

  public ByteBuf content() {
    return content;
  }

  public String contentUtf8() {
    return content == null ? null : content.toString(UTF_8);
  }

  public void release() {
    headers = null;
    if (hasContent()) {
      content.release();
    }
  }

  @SuppressWarnings("unchecked")
  protected final T self() {
    return (T) this;
  }

  String headersToString() {
    final int n = numHeaders();
    if (n == 0) {
      return "{}";
    }
    final StringBuilder s = new StringBuilder().append('{');
    int i = 0;
    while (true) {
      if (headerIsTrailing(i)) {
        s.append("(trailing) ");
      }
      s.append(headerName(i)).append('=').append(headerValue(i));
      i++;
      if (i >= n) {
        s.append('}');
        return s.toString();
      }
      s.append(',').append(' ');
    }
  }
}
