package io.norberg.http2;

import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.norberg.http2.PseudoHeaders.METHOD;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import com.google.common.io.BaseEncoding;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.AsciiString;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class HpackDecoderTest {

  private static final AsciiString FOO = AsciiString.of("foo");
  private static final AsciiString BAR = AsciiString.of("bar");
  private static final AsciiString BAZ = AsciiString.of("baz");
  private static final AsciiString QUUX = AsciiString.of("quux");

  private static final int DYNAMIC_TABLE_START_IX = HpackStaticTable.length() + 1;

  @Mock HpackDecoder.Listener listener;

  @Test
  public void testDecodeStatic() throws Exception {
    final ByteBuf block = Unpooled.buffer();
    Hpack.writeIndexedHeaderField(block, HpackStaticTable.headerIndex(METHOD, GET.asciiName()));

    final HpackDecoder decoder = new HpackDecoder(0);
    decoder.decode(block, listener);

    verify(listener).header(Http2Header.of(METHOD, GET.asciiName(), false));
  }

  @Test
  public void testDecodeUnindexedNewName() throws Exception {
    final ByteBuf block = Unpooled.buffer();

    Hpack.writeLiteralHeaderFieldWithoutIndexingNewName(block, FOO, BAR);

    final HpackDecoder decoder = new HpackDecoder(0);
    decoder.decode(block, listener);

    verify(listener).header(Http2Header.of(FOO, BAR, false));

    // Verify that the header did not get indexed
    assertThat(decoder.tableLength(), is(0));
  }

  @Test
  public void testDecodeSensitiveIndexedName() throws Exception {
    final ByteBuf block = Unpooled.buffer();

    Hpack.writeLiteralHeaderFieldIncrementalIndexingNewName(block, FOO, BAR);
    Hpack.writeLiteralHeaderFieldNeverIndexed(block, DYNAMIC_TABLE_START_IX, BAZ);

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

    final ByteBuf block = Unpooled.buffer();

    Hpack.writeLiteralHeaderFieldIncrementalIndexingNewName(block, FOO, BAR);
    Hpack.writeLiteralHeaderFieldWithoutIndexing(block, DYNAMIC_TABLE_START_IX, QUUX);

    final HpackDecoder decoder = new HpackDecoder(maxHeaderTableSize);
    decoder.decode(block, listener);

    verify(listener).header(Http2Header.of(FOO, BAR, false));
    verify(listener).header(Http2Header.of(FOO, QUUX, false));

    // Verify that only the indexable header got indexed
    assertThat(decoder.tableLength(), is(1));
  }

  @Test
  public void testIndexing() throws Exception {
    final HpackDecoder decoder = new HpackDecoder(Integer.MAX_VALUE);

    // Encode and decode first block - literal
    final ByteBuf block1 = Unpooled.buffer();
    Hpack.writeLiteralHeaderFieldIncrementalIndexingNewName(block1, FOO, BAR);
    decoder.decode(block1, listener);
    verify(listener).header(Http2Header.of(FOO, BAR, false));
    reset(listener);

    // Encode and decode first block - indexed
    final ByteBuf block2 = Unpooled.buffer();
    Hpack.writeIndexedHeaderField(block2, DYNAMIC_TABLE_START_IX);
    decoder.decode(block2, listener);
    verify(listener).header(Http2Header.of(FOO, BAR, false));
  }

  @Test
  public void testTableSizeChange() throws Exception {
    final ByteBuf block = Unpooled.buffer();

    Hpack.writeDynamicTableSizeUpdate(block, 4711);

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