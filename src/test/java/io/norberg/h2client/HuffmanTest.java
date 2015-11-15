package io.norberg.h2client;

import org.junit.Ignore;
import org.junit.Test;

import java.util.Random;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.AsciiString;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
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
    final ByteBuf encoded = Unpooled.buffer();
    final ByteBuf decoded = Unpooled.buffer();
    final byte[] data = new byte[128];
    final ByteBuf input = Unpooled.wrappedBuffer(data);
    final Random r = new Random(4711);
    for (int i = 0; i < 100000; i++) {
      r.nextBytes(data);
      final int length = 1 + r.nextInt(data.length);
      input.setIndex(0, length);
      Huffman.encode(encoded, input);
      Huffman.decode(encoded, decoded);
      input.setIndex(0, length);
      assertThat(decoded, is(input));
      encoded.setIndex(0, 0);
      decoded.setIndex(0, 0);
    }
  }

  @Ignore
  @Test
  public void benchmarkEncodeDecodeBinary() throws Exception {
    final ByteBuf encoded = Unpooled.buffer();
    final ByteBuf decoded = Unpooled.buffer();
    final byte[] data = new byte[128];
    final ByteBuf input = Unpooled.wrappedBuffer(data);
    final Random r = new Random(4711);
    final long start = System.nanoTime();
    long totalDecoded = 0;
    long totalEncoded = 0;
    for (int i = 0; i < 1000000; i++) {
      r.nextBytes(data);
      final int length = 1 + r.nextInt(data.length);
      input.setIndex(0, length);
      Huffman.encode(encoded, input);
      totalEncoded += encoded.readableBytes();
      Huffman.decode(encoded, decoded);
      totalDecoded += decoded.readableBytes();
      input.setIndex(0, length);
      encoded.setIndex(0, 0);
      decoded.setIndex(0, 0);
    }
    final long end = System.nanoTime();
    final long duration = end - start;
    final long decodedBytesPerSecond = totalDecoded / NANOSECONDS.toSeconds(duration);
    final long encodedBytesPerSecond = totalEncoded / NANOSECONDS.toSeconds(duration);
    System.out.printf("decoded throughput: %,d bytes/s%n", decodedBytesPerSecond);
    System.out.printf("encoded throughput: %,d bytes/s%n", encodedBytesPerSecond);
  }
}