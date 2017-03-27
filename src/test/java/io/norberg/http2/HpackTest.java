package io.norberg.http2;

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
import static io.norberg.http2.Hpack.writeInteger;
import static java.nio.charset.StandardCharsets.US_ASCII;
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
    final AsciiString authority = AsciiString.of("www.example.com");
    final AsciiString path = AsciiString.of("/index.html");

    final ByteBuf buf = Unpooled.buffer();
    writeMethod(buf, method);
    writeScheme(buf, scheme);
    writeAuthority(buf, authority);
    writePath(buf, path);

    final Decoder decoder = new Decoder(1024, 1024);
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
      assertThat(Hpack.readInteger(buf, n), is(i));
    }
  }

  @Test
  public void testHuffmanDecode() throws Exception {
    final String expected = "https://www.example.com";

    final byte[] encoded = TestUtil.bytes(
        0x9d, 0x29, 0xad, 0x17, 0x18, 0x63, 0xc7, 0x8f, 0x0b, 0x97, 0xc8, 0xe9, 0xae, 0x82, 0xae, 0x43, 0xd3);

    final ByteBuf in = Unpooled.wrappedBuffer(encoded);
    final ByteBuf out = Unpooled.buffer();

    Huffman.decode(in, out);

    final String decoded = out.toString(US_ASCII);
    assertThat(decoded, is(expected));
  }

  @Test
  public void testHeaderTableSizeUpdate() throws Exception {
    for (int i = 0; i < 32; i++) {
      int size = 1 << i;
      ByteBuf buf = Unpooled.buffer();
      Hpack.writeDynamicTableSizeUpdate(buf, size);
      int updateSize = Hpack.dynamicTableSizeUpdateSize(size);
      assertThat(buf.readableBytes(), is(updateSize));
    }
  }

  @Test
  public void testIntegerSize() throws Exception {
    for (int n = 1; n <= 8; n++) {
      for (int i = 0; i < 32; i++) {
        for (int j = 0; j <= i; j++) {
          int value = Math.toIntExact((1L << j) - 1);
          ByteBuf buf = Unpooled.buffer();
          Hpack.writeInteger(buf, 0, n, value);
          int calculatedSize = Hpack.integerSize(n, value);
          final int actualSize = buf.readableBytes();
          assertThat(actualSize, is(calculatedSize));
        }
      }
    }
  }

  private void writeLiteralHeaderFieldIncrementalIndexingIndexedName(final ByteBuf buf, final int i) {
    writeInteger(buf, 0x40, 6, i);
  }

  private void writeString(final ByteBuf buf, final AsciiString s) {
    final int encodedLength = Huffman.encodedLength(s);
    if (encodedLength < s.length()) {
      Hpack.writeHuffmanString(buf, s, encodedLength);
    } else {
      Hpack.writeRawString(buf, s);
    }
  }

  private void writeMethod(final ByteBuf buf, final AsciiString method) {
    if (method.equals(HttpMethod.GET.asciiName())) {
      Hpack.writeIndexedHeaderField(buf, 2);
    } else if (method.equals(HttpMethod.POST.asciiName())) {
      Hpack.writeIndexedHeaderField(buf, 3);
    } else {
      writeLiteralHeaderFieldIncrementalIndexingIndexedName(buf, 2);
      writeString(buf, method);
    }
  }

  private void writeScheme(final ByteBuf buf, final AsciiString scheme) {
    if (scheme.equals(SCHEME_HTTP)) {
      Hpack.writeIndexedHeaderField(buf, 6);
    } else if (scheme.equals(SCHEME_HTTPS)) {
      Hpack.writeIndexedHeaderField(buf, 7);
    } else {
      writeLiteralHeaderFieldIncrementalIndexingIndexedName(buf, 2);
      writeString(buf, scheme);
    }
  }

  private void writeAuthority(final ByteBuf buf, final AsciiString s) {
    writeLiteralHeaderFieldIncrementalIndexingIndexedName(buf, 1);
    writeString(buf, s);
  }

  private void writePath(final ByteBuf buf, final AsciiString path) {
    if (path.equals(PATH_SLASH)) {
      Hpack.writeIndexedHeaderField(buf, 4);
    } else if (path.equals(PATH_INDEX_HTML)) {
      Hpack.writeIndexedHeaderField(buf, 5);
    } else {
      writeLiteralHeaderFieldIncrementalIndexingIndexedName(buf, 4);
      writeString(buf, path);
    }
  }
}
