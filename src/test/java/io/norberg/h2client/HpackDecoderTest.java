package io.norberg.h2client;

import com.google.common.io.BaseEncoding;

import com.twitter.hpack.Encoder;

import org.hamcrest.Matchers;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import java.io.OutputStream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import io.netty.util.AsciiString;

import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.METHOD;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

@RunWith(MockitoJUnitRunner.class)
public class HpackDecoderTest {

  public static final AsciiString FOO = AsciiString.of("foo");
  public static final AsciiString BAR = AsciiString.of("bar");
  public static final AsciiString BAZ = AsciiString.of("baz");
  public static final AsciiString QUUX = AsciiString.of("quux");

  @Mock HpackDecoder.Listener listener;

  @Test
  public void testDecodeStatic() throws Exception {
    final Encoder encoder = new Encoder(0);
    final ByteBuf block = Unpooled.buffer();
    final OutputStream os = new ByteBufOutputStream(block);
    encoder.encodeHeader(os, METHOD.value().array(), GET.asciiName().array(), false);

    final HpackDecoder decoder = new HpackDecoder(0);
    decoder.decode(block, listener);

    verify(listener).header(Http2Header.of(METHOD.value(), GET.asciiName(), false));
  }

  @Test
  public void testDecodeUnindexedNewName() throws Exception {
    final Encoder encoder = new Encoder(0);
    final ByteBuf block = Unpooled.buffer();
    final OutputStream os = new ByteBufOutputStream(block);
    encoder.encodeHeader(os, FOO.array(), BAR.array(), false);

    final HpackDecoder decoder = new HpackDecoder(0);
    decoder.decode(block, listener);

    verify(listener).header(Http2Header.of(FOO, BAR, false));

    // Verify that the header did not get indexed
    assertThat(decoder.tableLength(), is(0));
  }

  @Test
  public void testDecodeSensitiveIndexedName() throws Exception {
    final Encoder encoder = new Encoder(Integer.MAX_VALUE);
    final ByteBuf block = Unpooled.buffer();
    final OutputStream os = new ByteBufOutputStream(block);
    encoder.encodeHeader(os, FOO.array(), BAR.array(), false);
    encoder.encodeHeader(os, FOO.array(), BAZ.array(), true);

    final HpackDecoder decoder = new HpackDecoder(Integer.MAX_VALUE);
    decoder.decode(block, listener);

    verify(listener).header(Http2Header.of(FOO, BAR, false));
    verify(listener).header(Http2Header.of(FOO, BAZ, true));

    // Verify that only the indexable header got indexed
    assertThat(decoder.tableLength(), is(1));
  }

  @Test
  public void testDecodeLiteralIndexedName() throws Exception {
    // force "foo: quux" to not be indexed as it will exceed the max header table size
    final int maxHeaderTableSize = 32 + FOO.length() + BAR.length();

    final Encoder encoder = new Encoder(maxHeaderTableSize);
    final ByteBuf block = Unpooled.buffer();
    final OutputStream os = new ByteBufOutputStream(block);
    encoder.encodeHeader(os, FOO.array(), BAR.array(), false);
    encoder.encodeHeader(os, FOO.array(), QUUX.array(), false);

    final HpackDecoder decoder = new HpackDecoder(maxHeaderTableSize);
    decoder.decode(block, listener);

    verify(listener).header(Http2Header.of(FOO, BAR, false));
    verify(listener).header(Http2Header.of(FOO, QUUX, false));

    // Verify that only the indexable header got indexed
    assertThat(decoder.tableLength(), is(1));
  }

  @Test
  public void testIndexing() throws Exception {
    final Encoder encoder = new Encoder(Integer.MAX_VALUE);
    final HpackDecoder decoder = new HpackDecoder(Integer.MAX_VALUE);

    // Encode and decode first block
    final ByteBuf block1 = Unpooled.buffer();
    encoder.encodeHeader(new ByteBufOutputStream(block1), FOO.array(), BAR.array(), false);
    final int block1Size = block1.readableBytes();
    decoder.decode(block1, listener);
    verify(listener).header(Http2Header.of(FOO, BAR, false));
    reset(listener);

    // Encode and decode first block - should be indexed
    final ByteBuf block2 = Unpooled.buffer();
    encoder.encodeHeader(new ByteBufOutputStream(block2), FOO.array(), BAR.array(), false);
    final int block2Size = block2.readableBytes();
    assertThat(block2Size, is(Matchers.lessThan(block1Size)));
    decoder.decode(block2, listener);
    verify(listener).header(Http2Header.of(FOO, BAR, false));
  }

  @Test
  public void testTableSizeChange() throws Exception {
    final Encoder encoder = new Encoder(Integer.MAX_VALUE);
    final ByteBuf block = Unpooled.buffer();
    final OutputStream os = new ByteBufOutputStream(block);

    // Change header table size
    encoder.setMaxHeaderTableSize(os, 4711);

    final HpackDecoder decoder = new HpackDecoder(Integer.MAX_VALUE);
    decoder.decode(block, listener);

    assertThat(decoder.maxTableSize(), is(4711));
  }

  @Test
  public void testLiteralIndexedNameHeadersFromTheWild() throws Exception {
    final HpackDecoder decoder = new HpackDecoder(Integer.MAX_VALUE);
    final ByteBuf block = Unpooled.wrappedBuffer(BaseEncoding.base16().lowerCase().decode("08033230300f0d03313038"));
    decoder.decode(block, listener);
    verify(listener).header(Http2Header.of(":status", AsciiString.of(String.valueOf(200))));
    verify(listener).header(Http2Header.of("content-length", AsciiString.of(String.valueOf(108))));
  }
}