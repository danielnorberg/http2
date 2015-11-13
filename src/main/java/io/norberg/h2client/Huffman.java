package io.norberg.h2client;

import io.netty.buffer.ByteBuf;
import io.netty.util.ByteString;

import static io.norberg.h2client.HuffmanTable.DECODE1;
import static io.norberg.h2client.HuffmanTable.LENGTHS;

class Huffman {

  static final int EOS = 0xFF;

  static void encode(final ByteBuf buf, final ByteString s) {
    encode(buf, s.array(), s.arrayOffset(), s.length());
  }

  static void encode(final ByteBuf buf, final byte[] bytes, final int offset, final int length) {
    long bits = 0;
    int n = 0;

    for (int i = 0; i < length; i++) {
      final int b = bytes[offset + i] & 0xFF;
      final int c = HuffmanTable.CODES[b];
      final int l = HuffmanTable.LENGTHS[b];

      bits <<= l;
      bits |= c;
      n += l;

      while (n >= 8) {
        n -= 8;
        buf.writeByte((int) (bits >> n));
      }
    }

    assert n < 8;
    if (n > 0) {
      bits <<= (8 - n);
      bits |= (EOS >>> n);
      buf.writeByte((int) bits);
    }
  }

  static int encodedLength(final ByteString s) {
    return encodedLength(s.array(), s.arrayOffset(), s.length());
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
    int bits = 0;
    int n = 0;

    int r;
    int b;

    while (true) {

      // Fill buffer
      if (n < 8) {
        if (!in.isReadable()) {
          break;
        }
        bits <<= 8;
        bits |= in.readUnsignedByte();
        n += 8;
      }

      // Get first 8 bits in buffer
      r = n - 8;
      b = (bits >> r);

      if (b == 0xFF) {
        throw new UnsupportedOperationException("next level");
      } else if (b == 0xFE) {

        // Remove first 8 bits from buffer
        bits &= (0xFFFFFFFF >> (32 - r));
        n -= 8;

        // Fill buffer
        if (n < 2) {
          if (!in.isReadable()) {
            throw new IllegalArgumentException();
          }
          bits <<= 8;
          bits |= in.readUnsignedByte();
          n += 8;
        }

        // Get two first bits in buffer
        r = n - 2;
        b = (bits >> r);

        // Remove first two bits from buffer
        bits &= (0xFFFFFFFF >>> (32 - r));
        n -= 2;

        final int value;
        if (b == 0b00) {
          value = 33;
        } else if (b == 0b01) {
          value = 34;
        } else if (b == 0b10) {
          value = 40;
        } else { // 0b11
          value = 41;
        }

        out.writeByte(value);

      } else {
        final int d = DECODE1[b];
        if (d == -1) {
          throw new IllegalArgumentException();
        }
        final int length = d >> 8;
        final int value = d & 0xFF;

        // Remove used bits from buffer
        r = n - length;
        bits &= (0xFFFFFFFF >>> (32 - r));
        n -= length;

        out.writeByte(value);
      }

    }

    // Consume trailing bits
    if (n > 0) {
      r = 8 - n;
      bits <<= r;
      bits |= (0xFF >> (8 - r));
      if (bits != 0xFF) {
        final int d = DECODE1[bits];
        if (d == -1) {
          throw new IllegalArgumentException();
        }
        final int value = d & 0xFF;
        out.writeByte(value);
      }
    }
  }
}
