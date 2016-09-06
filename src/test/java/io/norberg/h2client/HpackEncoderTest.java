package io.norberg.h2client;

import com.google.common.collect.ImmutableList;

import com.koloboke.collect.hash.HashConfig;
import com.twitter.hpack.Decoder;
import com.twitter.hpack.HeaderListener;

import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.util.AsciiString;
import io.norberg.h2client.benchmarks.ProgressMeter;

import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpScheme.HTTPS;
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.AUTHORITY;
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.METHOD;
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.PATH;
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.SCHEME;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@RunWith(MockitoJUnitRunner.class)
public class HpackEncoderTest {

  @Mock HeaderListener listener;

  private static final AsciiString FOO = AsciiString.of("foo");
  private static final AsciiString BAR = AsciiString.of("bar");
  private static final AsciiString BAZ = AsciiString.of("baz");

  @Test
  public void testEncodeStatic() throws Exception {

    // Encode
    final HpackEncoder encoder = new HpackEncoder(0);
    final ByteBuf block = Unpooled.buffer();
    encoder.encodeHeader(block, METHOD.value(), GET.asciiName(), false);

    // Decode
    final InputStream is = new ByteBufInputStream(block);
    final Decoder decoder = new Decoder(Integer.MAX_VALUE, Integer.MAX_VALUE);
    decoder.decode(is, listener);

    verify(listener).addHeader(METHOD.value().array(), GET.asciiName().array(), false);
    verifyNoMoreInteractions(listener);
  }

  @Test
  public void testIndexing() throws Exception {

    final HpackEncoder encoder = new HpackEncoder(Integer.MAX_VALUE);
    final Decoder decoder = new Decoder(Integer.MAX_VALUE, Integer.MAX_VALUE);

    final ByteBuf block1 = Unpooled.buffer();
    final ByteBuf block2 = Unpooled.buffer();

    // Insert into dynamic table
    encoder.encodeHeader(block1, FOO, BAR, false);
    encoder.encodeHeader(block1, BAR, FOO, false);
    encoder.encodeHeader(block1, FOO, BAZ, false);
    assertThat(encoder.tableLength(), is(3));

    // Use dynamic table
    encoder.encodeHeader(block2, BAR, FOO, false);
    encoder.encodeHeader(block2, FOO, BAZ, false);
    encoder.encodeHeader(block2, FOO, BAR, false);
    assertThat(block2.readableBytes(), is(lessThan(block1.readableBytes())));

    // Decode first block
    final InputStream is1 = new ByteBufInputStream(block1);
    decoder.decode(is1, listener);
    verify(listener).addHeader(FOO.array(), BAR.array(), false);
    verify(listener).addHeader(BAR.array(), FOO.array(), false);
    verify(listener).addHeader(FOO.array(), BAZ.array(), false);
    verifyNoMoreInteractions(listener);
    reset(listener);

    // Decode second block
    final InputStream is2 = new ByteBufInputStream(block2);
    decoder.decode(is2, listener);
    verify(listener).addHeader(FOO.array(), BAR.array(), false);
    verify(listener).addHeader(BAR.array(), FOO.array(), false);
    verify(listener).addHeader(FOO.array(), BAZ.array(), false);
    verifyNoMoreInteractions(listener);
  }

  @Test
  public void testEncodeRequest() throws Exception {
    final AsciiString method = GET.asciiName();
    final AsciiString scheme = HTTPS.name();
    final AsciiString authority = AsciiString.of("www.test.com");
    final AsciiString path = AsciiString.of("/");

    // Encode
    final HpackEncoder encoder = new HpackEncoder(Integer.MAX_VALUE);
    final ByteBuf block = Unpooled.buffer();
    encoder.encodeRequest(block, method, scheme, authority, path);

    final InputStream is = new ByteBufInputStream(block);
    final Decoder decoder = new Decoder(Integer.MAX_VALUE, Integer.MAX_VALUE);
    decoder.decode(is, listener);

    verify(listener).addHeader(METHOD.value().array(), method.array(), false);
    verify(listener).addHeader(SCHEME.value().array(), scheme.array(), false);
    verify(listener).addHeader(AUTHORITY.value().array(), authority.array(), false);
    verify(listener).addHeader(PATH.value().array(), path.array(), false);
    verifyNoMoreInteractions(listener);
  }

  @Test
  public void testOverflow() throws Exception {

    // TODO: optimize index

    final int n = 16 * 1024;

    final Http2Headers headers = new DefaultHttp2Headers();
    for (int i = 0; i < n; i++) {
      headers.add(AsciiString.of("header" + i), AsciiString.of("value" + i));
    }

    ByteBuf block = Unpooled.buffer();
    final HpackEncoder encoder = new HpackEncoder(4096);
    final List<Map.Entry<CharSequence, CharSequence>> headerList = ImmutableList.copyOf(headers);
    for (int i = 0; i < headers.size(); i++) {
      final Map.Entry<CharSequence, CharSequence> header = headerList.get(i);
      encoder.encodeHeader(block, AsciiString.of(header.getKey()), AsciiString.of(header.getValue()));
    }
  }

  @Ignore
  @Test
  public void testIndex() throws Exception {

    // TODO: optimize index

    final int n = 16 * 1024;

    final List<Http2Header> headers = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      headers.add(Http2Header.of(AsciiString.of("header" + i), AsciiString.of("value" + i)));
    }

    final ProgressMeter meter = new ProgressMeter();
    final ProgressMeter.Metric messages = meter.group("throughput").metric("headers", "headers");

    final HpackDynamicTableIndex index = new HpackDynamicTableIndex(1024);
    final Queue<Http2Header> q = new ArrayDeque<>();
    for (int i = 0; i < 50; i++) {
      final Http2Header header = headers.get(i);
      index.add(header);
      q.add(header);
    }
    while (true) {
      long start = System.nanoTime();
      for (int i = 0; i < headers.size(); i++) {
        int ix = q.size();
        final Http2Header last = q.remove();
        index.remove(last, ix);
        final Http2Header header = headers.get(i);
        index.add(header);
        q.add(header);
      }
      long end = System.nanoTime();
      messages.add(n, end - start);
    }
  }

  @Ignore
  @Test
  public void testIndex3() throws Exception {

    // TODO: optimize index

    final int n = 16 * 1024;

    final List<Http2Header> headers = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      headers.add(Http2Header.of(AsciiString.of("header" + i), AsciiString.of("value" + i)));
    }

    final ProgressMeter meter = new ProgressMeter();
    final ProgressMeter.Metric messages = meter.group("throughput").metric("headers", "headers");

    final HpackDynamicTable table = new HpackDynamicTable();
    final HpackDynamicTableIndex2 index = new HpackDynamicTableIndex2(table);
    for (int i = 0; i < 50; i++) {
      final Http2Header header = headers.get(i);
      table.addFirst(header);
      index.insert(header);
    }
    while (true) {
      long start = System.nanoTime();
      for (int i = 0; i < headers.size(); i++) {
        table.removeLast();
        final Http2Header header = headers.get(i);
        table.addFirst(header);
        index.insert(header);
      }
      long end = System.nanoTime();
      messages.add(n, end - start);
    }
  }

  @Ignore
  @Test
  public void testIndex2() throws Exception {

    // TODO: optimize index

    final int n = 16 * 1024;

    final List<Http2Header> headers = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      headers.add(Http2Header.of(AsciiString.of("header" + i), AsciiString.of("value" + i)));
    }

    final ProgressMeter meter = new ProgressMeter();
    final ProgressMeter.Metric messages = meter.group("throughput").metric("messages", "messages");

    final KolobokeIndex index = KolobokeIndex.withExpectedSize(HashConfig.fromLoads(0.05, 0.07, 0.1), 16);
    final Queue<Http2Header> q = new ArrayDeque<>();
    int offset = 0;
    for (int i = 0; i < 50; i++) {
      final Http2Header header = headers.get(i);
      offset++;
      index.put(header, offset);
      index.put(header.name(), offset);
      q.add(header);
    }
    while (true) {
      long start = System.nanoTime();
      for (int i = 0; i < headers.size(); i++) {
        int ix = offset - q.size() + 1;
        final Http2Header last = q.remove();
        boolean r1 = index.remove(last, ix);
        if (!r1) {
          throw new AssertionError();
        }
        boolean r2 = index.remove(last.name(), ix);
        if (!r2) {
          throw new AssertionError();
        }
        final Http2Header header = headers.get(i);
        offset++;
        index.put(header, offset);
        index.put(header.name(), offset);
        q.add(header);
      }
      long end = System.nanoTime();
      messages.add(n, end - start);
    }
  }
}