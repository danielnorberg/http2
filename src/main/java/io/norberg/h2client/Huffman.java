package io.norberg.h2client;

import io.netty.buffer.ByteBuf;
import io.netty.util.ByteString;

import static io.norberg.h2client.HuffmanTable.CODES;
import static io.norberg.h2client.HuffmanTable.LENGTHS;
import static io.norberg.h2client.HuffmanTable.TERMINAL;

class Huffman {

  static final int EOS = 0xFF;

  static void encode(final ByteBuf buf, final ByteString s) {
    encode(buf, s, 0, s.length());
  }

  static void encode(final ByteBuf buf, final ByteString s, final int offset, final int length) {
    encode(buf, s.array(), s.arrayOffset() + offset, length);
  }

  static void encode(final ByteBuf buf, final byte[] bytes) {
    encode(buf, bytes, 0, bytes.length);
  }

  static void encode(final ByteBuf buf, final byte[] bytes, final int offset, final int length) {
    long bits = 0;
    int n = 0;

    for (int i = 0; i < length; i++) {
      final int b = bytes[offset + i] & 0xFF;
      final int c = CODES[b];
      final int l = LENGTHS[b];

      bits <<= l;
      bits |= c;
      n += l;

      while (n >= 8) {
        n -= 8;
        buf.writeByte((int) (bits >>> n));
      }
    }

    assert n < 8;
    if (n > 0) {
      bits <<= (8 - n);
      bits |= (EOS >>> n);
      buf.writeByte((int) bits);
    }
  }

  static void encode(final ByteBuf out, final ByteBuf in) {
    if (in.hasArray()) {
      encode(out, in.array(), in.arrayOffset(), in.readableBytes());
    } else {
      encode(out, in, in.readableBytes());
    }
  }

  static void encode(final ByteBuf out, final ByteBuf in, final int length) {
    long bits = 0;
    int n = 0;

    for (int i = 0; i < length; i++) {
      final int b = in.readUnsignedByte();
      final int c = CODES[b];
      final int l = LENGTHS[b];

      bits <<= l;
      bits |= c;
      n += l;

      while (n >= 8) {
        n -= 8;
        out.writeByte((int) (bits >>> n));
      }
    }

    assert n < 8;
    if (n > 0) {
      bits <<= (8 - n);
      bits |= (EOS >>> n);
      out.writeByte((int) bits);
    }
  }

  static int encodedLength(final ByteString s) {
    return encodedLength(s, 0, s.length());
  }

  static int encodedLength(final ByteString s, final int offset, final int length) {
    return encodedLength(s.array(), offset + s.arrayOffset(), length);
  }

  static int encodedLength(final byte[] bytes, final int offset, final int length) {
    int bits = 0;
    for (int i = 0; i < length; i++) {
      final byte c = bytes[offset + i];
      final int n = LENGTHS[c];
      bits += n;
    }
    // Return padded length in bytes
    return (bits + 7) >> 3;
  }

  static void decode(final ByteBuf in, final ByteBuf out) {
    decode(in, out, in.readableBytes());
  }

  static void decode(final ByteBuf in, final ByteBuf out, final int length) {
    int bits = 0;
    int n = 0;
    int i = 0;

    int table = 0;

    while (true) {

      // Read the next byte from input if necessary
      if (n < 8) {
        if (i == length) {
          break;
        }
        bits = (bits << 8) | in.readUnsignedByte();
        n += 8;
        i++;
      }

      // Get first 8 bits in buffer
      final int r = n - 8;
      final int b = (bits >>> r) & 0xFF;

      // Look up node
      final int node = HuffmanTable.node(table, b);

      final boolean terminal = (node & TERMINAL) != 0;
      if (terminal) {
        // Extract value and number of used bits
        final int value = node & 0xFF;
        final int used = (node ^ TERMINAL) >>> 8;

        // Consume used bits
        n -= used;

        // Write decoded value to output
        out.writeByte(value);

        // Start decoding the next sequence
        table = 0;

        continue;
      }

      // Consume used bits
      n -= 8;

      // Move on to the next lookup in the sequence
      table = node;
    }

    // Consume trailing bits
    while (n > 0) {
      final int r = 8 - n;
      final int fill = 0xFF >> (8 - r);
      final int b = (bits << r) & 0xFF | fill;
      if (table == 0 && b == 0xFF) {
        break;
      }
      final int node = HuffmanTable.node(table, b);
      final boolean terminal = (node & TERMINAL) != 0;
      // There's no more bytes to read so this must be a terminal node
      if (!terminal) {
        throw new IllegalArgumentException();
      }
      // Extract value and number of used bits
      final int value = node & 0xFF;
      final int used = (node ^ TERMINAL) >>> 8;

      // Consume used bits
      n -= used;

      // Write decoded value to output
      out.writeByte(value);

      // Start decoding the next sequence
      table = 0;
    }
  }
}
