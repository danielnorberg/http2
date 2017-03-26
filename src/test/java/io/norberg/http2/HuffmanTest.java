package io.norberg.http2;

import org.junit.Ignore;
import org.junit.Test;

import java.util.Random;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.util.AsciiString;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

public class HuffmanTest {

  @Test
  public void testEncodeDecodeUnpooledHeap() throws Exception {
    final ByteBuf in = Unpooled.buffer();
    final ByteBuf out = Unpooled.buffer();
    testEncodeDecode(in, out);
  }

  @Test
  public void testEncodeDecodePooledDirect() throws Exception {
    final ByteBuf in = PooledByteBufAllocator.DEFAULT.directBuffer();
    final ByteBuf out = PooledByteBufAllocator.DEFAULT.directBuffer();
    System.out.println("hasMemoryAddress: " + in.hasMemoryAddress());
    testEncodeDecode(in, out);
  }

  private void testEncodeDecode(final ByteBuf encoded, final ByteBuf decoded) {
    final AsciiString input = AsciiString.of("foobarbazquux");
    Huffman.encode(encoded, input);
    Huffman.decode(encoded, decoded);
    assertThat(encoded.readableBytes(), is(0));
    encoded.readerIndex(0);
    final AsciiString output1 = new AsciiString(decoded.nioBuffer());
    final AsciiString output2 = Huffman.decode(encoded);
    assertThat(encoded.readableBytes(), is(0));
    assertThat(output1, is(input));
    assertThat(output2, is(input));
  }

  @Test
  public void testEncodeDecodeBinary() throws Exception {
    final ByteBuf encoded = Unpooled.buffer();
    final ByteBuf decoded1 = Unpooled.buffer();
    final byte[] data = new byte[128];
    final ByteBuf input = Unpooled.wrappedBuffer(data);
    final Random r = new Random(4711);
    for (int i = 0; i < 100000; i++) {
      r.nextBytes(data);
      final int length = 1 + r.nextInt(data.length);
      input.setIndex(0, length);
      Huffman.encode(encoded, input);
      Huffman.decode(encoded, decoded1);
      encoded.readerIndex(0);
      AsciiString decoded2 = Huffman.decode(encoded);
      input.setIndex(0, length);
      assertThat(decoded1, is(input));
      AsciiString expected = new AsciiString(data, 0, length, false);
      assertThat(decoded2, is(expected));
      encoded.setIndex(0, 0);
      decoded1.setIndex(0, 0);
    }
  }

  @Ignore
  @Test
  public void benchmarkEncodeDecodeBinary1() throws Exception {
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

  @Ignore
  @Test
  public void benchmarkEncodeDecodeBinary2() throws Exception {
    final ByteBuf encoded = Unpooled.buffer();
    final byte[] decoded = new byte[128];
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
      int n = Huffman.decode(encoded, decoded);
      totalDecoded += n;
      input.setIndex(0, length);
      encoded.setIndex(0, 0);
    }
    final long end = System.nanoTime();
    final long duration = end - start;
    final long decodedBytesPerSecond = totalDecoded / NANOSECONDS.toSeconds(duration);
    final long encodedBytesPerSecond = totalEncoded / NANOSECONDS.toSeconds(duration);
    System.out.printf("decoded throughput: %,d bytes/s%n", decodedBytesPerSecond);
    System.out.printf("encoded throughput: %,d bytes/s%n", encodedBytesPerSecond);
  }
}