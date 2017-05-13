package io.norberg.http2.benchmarks;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.base64.Base64;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

class BenchmarkUtil {

  static ByteBuf[] payloads(final int size, final int n) {
    return IntStream.range(0, n).mapToObj(i -> payload(size)).toArray(ByteBuf[]::new);
  }

  static ByteBuf payload(final int size) {
    final int binarySize = size * 3 / 4;
    final ThreadLocalRandom r = ThreadLocalRandom.current();
    final ByteBuf binary = Unpooled.buffer(size);
    for (int i = 0; i < binarySize; i++) {
      binary.writeChar(r.nextInt());
    }
    // Base64 encode to make the payloads easier on the eye when debugging
    final ByteBuf encoded = Base64.encode(binary);
    return Unpooled.unreleasableBuffer(encoded);
  }

  static byte[][] arrayPayloads(final int size, final int n) {
    return IntStream.range(0, n).mapToObj(i -> arrayPayload(size)).toArray(byte[][]::new);
  }

  static byte[] arrayPayload(final int size) {
    final byte[] payload = new byte[size];
    ThreadLocalRandom.current().nextBytes(payload);
    return payload;
  }
}
