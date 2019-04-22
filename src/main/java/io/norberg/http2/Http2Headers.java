package io.norberg.http2;

import io.netty.util.AsciiString;
import java.util.AbstractMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public interface Http2Headers {

  Http2Headers EMPTY = new EmptyHttp2Headers();

  int size();

  boolean isEmpty();

  AsciiString name(int i);

  AsciiString value(int i);

  Http2Headers add(AsciiString name, AsciiString value);

  Http2Headers add(String name, String value);

  Http2Headers add(Http2Header header);

  default Http2Headers addAll(Map<AsciiString, AsciiString> headers) {
    headers.forEach(this::add);
    return this;
  }

  default Stream<Map.Entry<AsciiString, AsciiString>> stream() {
    return IntStream.range(0, size())
        .mapToObj(i -> new AbstractMap.SimpleImmutableEntry<>(name(i), value(i)));
  }

  void forEachHeader(BiConsumer<AsciiString, AsciiString> action);

  static Http2Headers of() {
    return DefaultHttp2Headers.of();
  }

  static Http2Headers of(Map<AsciiString, AsciiString> headers) {
    return DefaultHttp2Headers.of(headers);
  }
}
