package io.norberg.http2;

import static io.norberg.http2.HuffmanTable.CODES;
import static io.norberg.http2.HuffmanTable.LENGTHS;
import static io.norberg.http2.HuffmanTable.TERMINAL;

import io.netty.buffer.ByteBuf;
import io.netty.util.AsciiString;
import io.netty.util.internal.PlatformDependent;

class Huffman {

  static final int EOS = 0xFFFFFFFF;

  private static final boolean UNALIGNED = PlatformDependent.isUnaligned();

  static void encode(final ByteBuf out, final AsciiString s) {
    encode(out, s, 0, s.length());
  }

  static void encode(final ByteBuf out, final AsciiString s, final int offset, final int length) {
    encode(out, s.array(), s.arrayOffset() + offset, length);
  }

  static void encode(final ByteBuf out, final byte[] bytes) {
    encode(out, bytes, 0, bytes.length);
  }

  static void encode(final ByteBuf out, final byte[] bytes, final int offset, final int length) {
    long bits = 0;
    int n = 0;

    for (int i = 0; i < length; i++) {
      final int b = bytes[offset + i] & 0xFF;
      final int c = CODES[b];
      final int l = LENGTHS[b];

      bits <<= l;
      bits |= c;
      n += l;

      if (n >= 32) {
        n -= 32;
        out.writeInt((int) (bits >>> n));
      }
    }

    assert n < 32;
    if (n > 0) {
      int mark = out.writerIndex();
      int remainder = ((n + 7) >>> 3);
      bits <<= (32 - n);
      bits |= (EOS >>> n);
      out.writeInt((int) bits);
      out.writerIndex(mark + remainder);
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

      if (n >= 32) {
        n -= 32;
        out.writeInt((int) (bits >>> n));
      }
    }

    assert n < 32;
    if (n > 0) {
      int mark = out.writerIndex();
      int remainder = ((n + 7) >>> 3);
      bits <<= (32 - n);
      bits |= (EOS >>> n);
      out.writeInt((int) bits);
      out.writerIndex(mark + remainder);
    }
  }

  static int encodedLength(final AsciiString s) {
    return encodedLength(s, 0, s.length());
  }

  static int encodedLength(final AsciiString s, final int offset, final int length) {
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
    long bits = 0;
    int n = 0;
    int i = 0;

    int table = 0;

    while (true) {

      // Read the next byte from input if necessary
      if (n < 8) {
        if (i == length) {
          break;
        }
        if (length - i >= 4) {
          bits = (bits << 32) | in.readUnsignedInt();
          n += 32;
          i += 4;
        } else {
          bits = (bits << 8) | in.readUnsignedByte();
          n += 8;
          i++;
        }
      }

      // Get first 8 bits in buffer
      final int r = n - 8;
      final int b = (int) ((bits >>> r) & 0xFF);

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
      final int b = (int) ((bits << r) & 0xFF | fill);
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

  static AsciiString decode(final ByteBuf in) {
    return decode(in, in.readableBytes());
  }

  static AsciiString decode(final ByteBuf in, final int length) {
    final byte[] out = new byte[length * 2];
    final int n = decode(in, out, length);
    return new AsciiString(out, 0, n, false);
  }

  static int decode(final ByteBuf in, final byte[] out) {
    return decode(in, out, in.readableBytes());
  }

  static int decode(final ByteBuf in, final byte[] out, final int length) {
    long bits = 0;
    int n = 0;
    int i = 0;

    int table = 0;

    int j = 0;

    while (true) {

      // Read the next byte from input if necessary
      if (n < 8) {
        if (i == length) {
          break;
        }
        if (length - i >= 4) {
          bits = (bits << 32) | in.readUnsignedInt();
          n += 32;
          i += 4;
        } else {
          bits = (bits << 8) | in.readUnsignedByte();
          n += 8;
          i++;
        }
      }

      // Get first 8 bits in buffer
      final int r = n - 8;
      final int b = (int) ((bits >>> r) & 0xFF);

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
        out[j] = (byte) value;
        j++;

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
      final int b = (int) ((bits << r) & 0xFF | fill);
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
      out[j] = (byte) value;
      j++;

      // Start decoding the next sequence
      table = 0;
    }
    return j;
  }

}
