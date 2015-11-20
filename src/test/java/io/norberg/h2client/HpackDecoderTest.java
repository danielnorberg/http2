package io.norberg.h2client;

import com.twitter.hpack.Encoder;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import java.io.OutputStream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import io.netty.util.AsciiString;

import static org.mockito.Mockito.verify;

@RunWith(MockitoJUnitRunner.class)
public class HpackDecoderTest {

  public static final AsciiString FOO = AsciiString.of("foo");
  public static final AsciiString BAR = AsciiString.of("bar");

  @Mock HpackDecoder.Listener listener;

  @Before
  public void setUp() throws Exception {
  }

  @Test
  public void testDecode() throws Exception {
    final Encoder encoder = new Encoder(0);
    final ByteBuf block = Unpooled.buffer();
    final OutputStream os = new ByteBufOutputStream(block);
    encoder.encodeHeader(os, FOO.array(), BAR.array(), false);

    final HpackDecoder decoder = new HpackDecoder();
    decoder.decode(block, listener);

    verify(listener).header(Http2Header.of(FOO, BAR, false));
  }
}