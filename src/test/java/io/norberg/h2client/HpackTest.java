package io.norberg.h2client;

import com.twitter.hpack.Decoder;
import com.twitter.hpack.HeaderListener;

import org.junit.Test;
import org.junit.experimental.theories.Theories;
import org.junit.experimental.theories.Theory;
import org.junit.experimental.theories.suppliers.TestedOn;
import org.junit.runner.RunWith;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.util.AsciiString;

import static io.netty.handler.codec.http.HttpMethod.GET;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@RunWith(Theories.class)
public class HpackTest {

  private static final AsciiString PATH_SLASH = AsciiString.of("/");
  private static final AsciiString PATH_INDEX_HTML = AsciiString.of("/index.html");
  private static final AsciiString SCHEME_HTTP = AsciiString.of("http");
  private static final AsciiString SCHEME_HTTPS = AsciiString.of("https");

  private static final AsciiString HEADER_METHOD = AsciiString.of(":method");
  private static final AsciiString HEADER_SCHEME = AsciiString.of(":scheme");
  private static final AsciiString HEADER_AUTHORITY = AsciiString.of(":authority");
  private static final AsciiString HEADER_PATH = AsciiString.of(":path");

  @Test
  public void testStaticEncoding() throws Exception {

    final AsciiString method = GET.asciiName();
    final AsciiString scheme = SCHEME_HTTPS;
    final AsciiString authority = AsciiString.of("www.test.com");
    final AsciiString path = AsciiString.of("/index.html");

    final ByteBuf buf = Unpooled.buffer();
    writeMethod(buf, method);
    writeScheme(buf, scheme);
    writeAuthority(buf, authority);
    writePath(buf, path);

    final Decoder decoder = new Decoder(Integer.MAX_VALUE, Integer.MAX_VALUE);
    final ByteBufInputStream in = new ByteBufInputStream(buf);
    final HeaderListener listener = mock(HeaderListener.class);
    decoder.decode(in, listener);

    verify(listener).addHeader(HEADER_METHOD.array(), method.array(), false);
    verify(listener).addHeader(HEADER_SCHEME.array(), scheme.array(), false);
    verify(listener).addHeader(HEADER_AUTHORITY.array(), authority.array(), false);
    verify(listener).addHeader(HEADER_PATH.array(), path.array(), false);

    verifyNoMoreInteractions(listener);
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
   * encode (2^N - 1) on N bits
   * I = I - (2^N - 1)
   * while I >= 128
   * encode (I % 128 + 128) on 8 bits
   * I = I / 128
   * encode I on 8 bits
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
   * M = 0
   * repeat
   * B = next octet
   * I = I + (B & 127) * 2^M
   * M = M + 7
   * while B & 128 == 128
   * return I
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

  private void writeIndexedHeaderField(final ByteBuf buf, final int i) {
    writeInteger(buf, 0x80, 7, i);
  }

  private void writeLiteralHeaderFieldIncrementalIndexingIndexedName(final ByteBuf buf, final int i) {
    writeInteger(buf, 0x40, 6, i);
  }

  private void writeRawString(final ByteBuf buf, final AsciiString s) {
    writeInteger(buf, 0, 7, s.length());
    buf.writeBytes(s.array(), s.arrayOffset(), s.length());
  }

  private void writeMethod(final ByteBuf buf, final AsciiString method) {
    if (method.equals(HttpMethod.GET.asciiName())) {
      writeIndexedHeaderField(buf, 2);
    } else if (method.equals(HttpMethod.POST.asciiName())) {
      writeIndexedHeaderField(buf, 3);
    } else {
      writeLiteralHeaderFieldIncrementalIndexingIndexedName(buf, 2);
      writeRawString(buf, method);
    }
  }

  private void writeScheme(final ByteBuf buf, final AsciiString scheme) {
    if (scheme.equals(SCHEME_HTTP)) {
      writeIndexedHeaderField(buf, 6);
    } else if (scheme.equals(SCHEME_HTTPS)) {
      writeIndexedHeaderField(buf, 7);
    } else {
      writeLiteralHeaderFieldIncrementalIndexingIndexedName(buf, 2);
      writeRawString(buf, scheme);
    }
  }

  private void writeAuthority(final ByteBuf buf, final AsciiString s) {
    writeLiteralHeaderFieldIncrementalIndexingIndexedName(buf, 1);
    writeRawString(buf, s);
  }

  private void writePath(final ByteBuf buf, final AsciiString path) {
    if (path.equals(PATH_SLASH)) {
      writeIndexedHeaderField(buf, 4);
    } else if (path.equals(PATH_INDEX_HTML)) {
      writeIndexedHeaderField(buf, 5);
    } else {
      writeLiteralHeaderFieldIncrementalIndexingIndexedName(buf, 4);
      writeRawString(buf, path);
    }
  }
}
