package io.norberg.h2client;

import io.netty.buffer.ByteBuf;
import io.netty.util.AsciiString;

import static io.norberg.h2client.HuffmanTable.LENGTHS;

class Huffman {

  public static final int EOS = 0xFF;

  public static void encode(final ByteBuf buf, final AsciiString s) {
    encode(buf, s.array(), s.arrayOffset(), s.length());
  }

  private static void encode(final ByteBuf buf, final byte[] bytes, final int offset, final int length) {
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

  public static int encodedLength(final AsciiString s) {
    return encodedLength(s.array(), s.arrayOffset(), s.length());
  }

  private static int encodedLength(final byte[] bytes, final int offset, final int length) {
    int bits = 0;
    for (int i = 0; i < length; i++) {
      final byte c = bytes[offset + i];
      final int n = LENGTHS[c];
      bits += n;
    }
    // Return padded length in bytes
    return (bits + 7) >> 3;
  }
}
