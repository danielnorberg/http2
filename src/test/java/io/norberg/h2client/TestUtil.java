package io.norberg.h2client;

class TestUtil {

  static byte[] bytes(final int... values) {
    final byte[] bytes = new byte[values.length];
    for (int i = 0; i < values.length; i++) {
      bytes[i] = (byte) values[i];
    }
    return bytes;
  }
}
