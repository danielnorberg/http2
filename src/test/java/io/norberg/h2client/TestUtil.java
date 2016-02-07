package io.norberg.h2client;

import java.util.concurrent.ThreadLocalRandom;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

class TestUtil {

  static byte[] bytes(final int... values) {
    final byte[] bytes = new byte[values.length];
    for (int i = 0; i < values.length; i++) {
      bytes[i] = (byte) values[i];
    }
    return bytes;
  }

  static ByteBuf randomByteBuf(final int size) {
    final byte[] bytes = new byte[size];
    ThreadLocalRandom.current().nextBytes(bytes);
    return Unpooled.wrappedBuffer(bytes);
  }
}
