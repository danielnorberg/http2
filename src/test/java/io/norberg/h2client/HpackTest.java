package io.norberg.h2client;

import org.junit.Ignore;
import org.junit.Test;
import org.junit.experimental.theories.Theories;
import org.junit.experimental.theories.Theory;
import org.junit.experimental.theories.suppliers.TestedOn;
import org.junit.runner.RunWith;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpMethod;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

@RunWith(Theories.class)
public class HpackTest {

  @Ignore("wip")
  @Test
  public void testStaticEncoding() throws Exception {
    final ByteBuf buf = Unpooled.buffer();
    writeMethod(buf, HttpMethod.GET);
    writeScheme(buf, "https");
    writeAuthority(buf, "www.test.com");
    writePath(buf, "/index.html");
  }

  private void writeScheme(final ByteBuf buf, final String scheme) {
    switch (scheme) {
      case "http":
        writeInteger(buf, 0x1, 7, 6);
        return;
      case "https":
        writeInteger(buf, 0x1, 7, 7);
        return;
      default:
        throw new UnsupportedOperationException();
    }
  }

  @Theory
  public void testWriteReadInteger(
      @TestedOn(ints = {1, 2, 3, 4, 5, 6, 7, 8}) int n,
      @TestedOn(ints = {0, 1, 17, 4711, 80087, 22193521}) int i
  ) throws Exception {
    final int maxMaskBits = 8 - n;
    for (int maskBits = 0; maskBits < maxMaskBits; maskBits++) {
      final int mask = 0xFF << (8 - maskBits);
      final ByteBuf buf = Unpooled.buffer();
      writeInteger(buf, mask, n, i);
      assertThat(readInteger(buf, n), is(i));
    }
  }

  /**
   * if I < 2^N - 1, encode I on N bits
   * else
   *     encode (2^N - 1) on N bits
   *     I = I - (2^N - 1)
   *     while I >= 128
   *          encode (I % 128 + 128) on 8 bits
   *          I = I / 128
   *     encode I on 8 bits
   */
  private void writeInteger(final ByteBuf buf, final int mask, final int n, int i) {
    final int maskBits = 8 - n;
    final int nMask = (0xFF >> maskBits);
    if (i < nMask) {
      buf.writeByte(mask | i);
      return;
    }

    buf.writeByte(mask | nMask);
    i = i - nMask;
    while (i >= 0x80) {
      buf.writeByte((i & 0x7F) + 0x80);
      i >>= 7;
    }
    buf.writeByte(i);
  }

  /**
   * decode I from the next N bits
   * if I < 2^N - 1, return I
   * else
   *     M = 0
   *     repeat
   *         B = next octet
   *         I = I + (B & 127) * 2^M
   *         M = M + 7
   *     while B & 128 == 128
   *     return I
   */
  private int readInteger(final ByteBuf buf, final int n) {

    final int maskBits = 8 - n;
    final int nMask = (0xFF >> maskBits);
    int i = buf.readUnsignedByte() & nMask;
    if (i < nMask) {
      return i;
    }

    int m = 0;
    int b;
    do {
      b = buf.readUnsignedByte();
      i += (b & 0x7F) << m;
      m = m + 7;
    } while ((b & 0x80) == 0x80);
    return i;
  }

  private void writePath(final ByteBuf buf, final String path) {

  }

  private void writeMethod(final ByteBuf buf, final HttpMethod method) {

  }

  private void writeAuthority(final ByteBuf buf, final CharSequence s) {

  }
}
