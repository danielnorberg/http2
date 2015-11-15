package io.norberg.h2client;

import org.junit.Test;

import java.util.Random;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.AsciiString;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

public class HuffmanTest {

  @Test
  public void testEncodeDecode() throws Exception {
    final AsciiString input = AsciiString.of("foobar");
    final ByteBuf encoded = Unpooled.buffer();
    final ByteBuf decoded = Unpooled.buffer();
    Huffman.encode(encoded, input);
    Huffman.decode(encoded, decoded);
    final AsciiString output = new AsciiString(decoded.nioBuffer());
    assertThat(output, is(input));

  }

  @Test
  public void testEncodeDecodeBinary() throws Exception {
    final Random r = new Random(4711);
    for (int i = 0; i < 10000; i++) {
      final ByteBuf encoded = Unpooled.buffer();
      final ByteBuf decoded = Unpooled.buffer();
      final byte[] data = new byte[1 + r.nextInt(4096)];
      r.nextBytes(data);
      Huffman.encode(encoded, data);
      Huffman.decode(encoded, decoded);
      assertThat(decoded, is(Unpooled.wrappedBuffer(data)));
    }
  }
}