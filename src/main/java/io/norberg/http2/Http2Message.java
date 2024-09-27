package io.norberg.http2;

import static java.lang.Integer.max;
import static java.lang.Integer.numberOfLeadingZeros;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.AsciiString;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

abstract class Http2Message<T extends Http2Message<T>> {

  /**
   * A reference either to an array of {@link AsciiString} or a {@link Http2Headers}.
   */
  private Object headers;
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
    if (headers instanceof Http2Headers) {
      return ((Http2Headers) headers).name(i);
    }
    AsciiString[] headersArray = (AsciiString[]) headers;
    final int ix = i << 1;
    return headersArray[ix];
  }

  public AsciiString headerValue(int i) {
    if (headers == null) {
      throw new IndexOutOfBoundsException("Index: " + i + ", Size: " + 0);
    }
    if (headers instanceof Http2Headers) {
      return ((Http2Headers) headers).value(i);
    }
    AsciiString[] headersArray = (AsciiString[]) headers;
    final int ix = i << 1;
    return headersArray[ix + 1];
  }

  public T header(final String name, final String value) {
    return header(AsciiString.of(name), AsciiString.of(value));
  }

  public T header(final AsciiString name, final AsciiString value) {
    return initialHeader(name, value);
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

  public T trailingHeader(String name, String value) {
    return trailingHeader(AsciiString.of(name), AsciiString.of(value));
  }

  public T trailingHeader(AsciiString name, AsciiString value) {
    ++trailingHeaders;
    return header0(name, value);
  }

  private T header0(AsciiString name, AsciiString value) {
    if (headers == null) {
      headers = new AsciiString[16];
    } else if (headers instanceof Http2Headers) {
      headers = copyHeaders((Http2Headers) headers);
    }
    AsciiString[] headersArray = (AsciiString[]) headers;
    if (headerIx >= headersArray.length) {
      headersArray = Arrays.copyOf(headersArray, headersArray.length * 2);
      headers = headersArray;
    }
    headersArray[headerIx] = name;
    headersArray[headerIx + 1] = value;
    headerIx += 2;
    return self();
  }

  private static AsciiString[] copyHeaders(Http2Headers headers) {
    int headersSize = headers.size();
    final int newSize = max(16, 1 << (32 - numberOfLeadingZeros(headersSize * 3)));
    final AsciiString[] newHeaders = new AsciiString[newSize];
    for (int i = 0; i < headersSize; i += 1) {
      newHeaders[i] = headers.name(i);
      newHeaders[i + 1] = headers.name(i);
    }
    return newHeaders;
  }

  public T content(final ByteBuf content) {
    this.content = content;
    return self();
  }

  public T contentUtf8(final String content) {
    return content(Unpooled.copiedBuffer(content, UTF_8));
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

  void addContent(ByteBuf data) {
    this.content = Util.appendBytes(content, data);
  }

  public T headers(Http2Headers headers) {
    if (trailingHeaders != 0) {
      throw new IllegalStateException("Cannot add headers after trailers");
    }
    if (this.headers != null) {
      headers.forEachHeader(this::header);
    } else {
      this.headers = headers;
      this.headerIx = headers.size() * 2;
    }
    return self();
  }

  public T trailers(Http2Headers headers) {
    if (this.headers != null) {
      headers.forEachHeader(this::trailingHeader);
    } else {
      this.headers = headers;
      this.trailingHeaders = headers.size();
    }
    return self();
  }

  public T headers(Iterable<Map.Entry<AsciiString, AsciiString>> headers) {
    headers.forEach(e -> header(e.getKey(), e.getValue()));
    return self();
  }

  public T headers(Stream<Map.Entry<AsciiString, AsciiString>> headers) {
    headers.forEach(e -> header(e.getKey(), e.getValue()));
    return self();
  }

  public T trailers(Iterable<Map.Entry<AsciiString, AsciiString>> headers) {
    headers.forEach(e -> trailingHeader(e.getKey(), e.getValue()));
    return self();
  }

  public T trailers(Stream<Map.Entry<AsciiString, AsciiString>> headers) {
    headers.forEach(e -> trailingHeader(e.getKey(), e.getValue()));
    return self();
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

  @SuppressWarnings("unchecked")
  protected T self() {
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
