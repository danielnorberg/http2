package io.norberg.h2client;

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

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

@RunWith(MockitoJUnitRunner.class)
public class HpackDecoderTest {

  public static final AsciiString FOO = AsciiString.of("foo");
  public static final AsciiString BAR = AsciiString.of("bar");

  public static final AsciiString METHOD = AsciiString.of(":method");
  public static final AsciiString GET = AsciiString.of("GET");

  @Mock HpackDecoder.Listener listener;

  @Test
  public void testDecodeStatic() throws Exception {
    final Encoder encoder = new Encoder(0);
    final ByteBuf block = Unpooled.buffer();
    final OutputStream os = new ByteBufOutputStream(block);
    encoder.encodeHeader(os, METHOD.array(), GET.array(), false);

    final HpackDecoder decoder = new HpackDecoder(0);
    decoder.decode(block, listener);

    verify(listener).header(Http2Header.of(METHOD, GET, false));
  }

  @Test
  public void testDecodeUnindexed() throws Exception {
    final Encoder encoder = new Encoder(0);
    final ByteBuf block = Unpooled.buffer();
    final OutputStream os = new ByteBufOutputStream(block);
    encoder.encodeHeader(os, FOO.array(), BAR.array(), false);

    final HpackDecoder decoder = new HpackDecoder(0);
    decoder.decode(block, listener);

    verify(listener).header(Http2Header.of(FOO, BAR, false));

    // Verify that the header did not get indexed
    assertThat(decoder.dynamicTableLength(), is(0));
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

    assertThat(decoder.dynamicTableSize(), is(4711));
  }
}