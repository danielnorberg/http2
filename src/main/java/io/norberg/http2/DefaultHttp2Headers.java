package io.norberg.http2;

import io.netty.util.AsciiString;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

class DefaultHttp2Headers<T extends DefaultHttp2Headers<T>> implements Http2Headers {

  private AsciiString[] headers;
  private int headerIx;

  @Override
  public AsciiString name(int i) {
    if (headers == null) {
      throw new IndexOutOfBoundsException("Index: " + i + ", Size: " + 0);
    }
    final int ix = i << 1;
    return headers[ix];
  }

  @Override
  public AsciiString value(int i) {
    if (headers == null) {
      throw new IndexOutOfBoundsException("Index: " + i + ", Size: " + 0);
    }
    final int ix = i << 1;
    return headers[ix + 1];
  }

  @Override
  public T add(final AsciiString name, final AsciiString value) {
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

  @Override
  public Http2Headers add(String name, String value) {
    return add(AsciiString.of(name), AsciiString.of(value));
  }

  @Override
  public Http2Headers add(Http2Header header) {
    return add(header.name(), header.value());
  }

  @Override
  public int size() {
    return headerIx >> 1;
  }

  @Override
  public boolean isEmpty() {
    return headerIx != 0;
  }

  public T headers(String... headers) {
    if ((headers.length & 1) != 0) {
      throw new IllegalArgumentException("Uneven number of header values");
    }
    AsciiString[] asciiHeaders = new AsciiString[headers.length];
    for (int i = 0; i < headers.length; i++) {
      asciiHeaders[i] = AsciiString.of(headers[i]);
    }
    return headers(asciiHeaders);
  }

  public T headers(AsciiString... headers) {
    if ((headers.length & 1) != 0) {
      throw new IllegalArgumentException("Uneven number of header values");
    }
    for (int i = 0; i < headers.length; i += 2) {
      add(headers[i], headers[i + 1]);
    }
    return self();
  }

  public T headers(Iterable<Map.Entry<AsciiString, AsciiString>> headers) {
    headers.forEach(e -> add(e.getKey(), e.getValue()));
    return self();
  }

  public T headers(Stream<Map.Entry<AsciiString, AsciiString>> headers) {
    headers.forEach(e -> add(e.getKey(), e.getValue()));
    return self();
  }

  public Stream<Map.Entry<AsciiString, AsciiString>> headers() {
    return IntStream.range(0, size())
        .mapToObj(i -> new AbstractMap.SimpleImmutableEntry<>(name(i), value(i)));
  }

  String headersToString() {
    final int n = size();
    if (n == 0) {
      return "{}";
    }
    final StringBuilder s = new StringBuilder().append('{');
    int i = 0;
    while (true) {
      s.append(name(i)).append('=').append(value(i));
      i++;
      if (i >= n) {
        s.append('}');
        return s.toString();
      }
      s.append(',').append(' ');
    }
  }

  @Override
  public void forEachHeader(BiConsumer<AsciiString, AsciiString> action) {
    Objects.requireNonNull(action);
    for (int i = 0; i < size(); i++) {
      action.accept(name(i), value(i));
    }
  }

  public void release() {
    if (headers != null) {
      Arrays.fill(headers, null);
      headers = null;
    }
  }

  @SuppressWarnings("unchecked")
  protected T self() {
    return (T) this;
  }

  static Http2Headers of() {
    return new DefaultHttp2Headers<>();
  }

  static Http2Headers of(Map<AsciiString, AsciiString> headers) {
    return new DefaultHttp2Headers<>().addAll(headers);
  }
}
