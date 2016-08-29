package io.norberg.h2client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.AsciiString;

import static java.lang.Integer.numberOfLeadingZeros;

class Hpack {

  static void writeInteger(final ByteBuf buf, final int mask, final int n, int i) {
    final int maskBits = 8 - n;
    final int nMask = (0xFF >> maskBits);
    if (i < nMask) {
      buf.writeByte(mask | i);
      return;
    }

    buf.writeByte(mask | nMask);
    i -= nMask;
    while (i >= 0x80) {
      buf.writeByte((i & 0x7F) | 0x80);
      i >>= 7;
    }
    buf.writeByte(i);
  }

  static int integerSize(final int n, int i) {
    final int maskBits = 8 - n;
    final int nMask = (0xFF >> maskBits);
    if (i < nMask) {
      return 1;
    }
    i -= nMask;
    return 2 + (32 - numberOfLeadingZeros(i >> 7) + 6) / 7;
  }

  static void writeString(final ByteBuf buf, final AsciiString s) {
    final int encodedLength = Huffman.encodedLength(s);
    if (encodedLength < s.length()) {
      writeHuffmanString(buf, s, encodedLength);
    } else {
      writeRawString(buf, s);
    }
  }

  static void writeHuffmanString(final ByteBuf buf, final AsciiString s, final int encodedLength) {
    writeInteger(buf, 0x80, 7, encodedLength);
    Huffman.encode(buf, s);
  }

  static void writeRawString(final ByteBuf buf, final AsciiString s) {
    writeInteger(buf, 0, 7, s.length());
    buf.writeBytes(s.array(), s.arrayOffset(), s.length());
  }

  static int dynamicTableSizeUpdateSize(final int tableSize) {
    return integerSize(5, tableSize);
  }

  static void writeDynamicTableSizeUpdate(final ByteBuf buf, final int size) {
    writeInteger(buf, 0x20, 5, size);
  }

  /**
   * 6.1.  Indexed Header Field Representation
   * https://tools.ietf.org/html/rfc7541#section-6.1
   */
  static void writeIndexedHeaderField(final ByteBuf buf, final int index) {
    writeInteger(buf, 0b1000_0000, 7, index);
  }

  static int readInteger(final ByteBuf buf, final int n) {
    final int b = buf.readUnsignedByte();
    return readInteger(b, buf, n);
  }

  static int readInteger(int prefix, final ByteBuf buf, final int n) {
    final int maskBits = 8 - n;
    final int nMask = (0xFF >> maskBits);
    prefix &= nMask;
    if (prefix < nMask) {
      return prefix;
    }

    int m = 0;
    int b;
    do {
      b = buf.readUnsignedByte();
      prefix += (b & 0x7F) << m;
      m = m + 7;
    } while ((b & 0x80) == 0x80);
    return prefix;
  }

  static AsciiString readAsciiString(final ByteBuf in) throws HpackDecodingException {
    final int b = in.readUnsignedByte();
    final int length = readInteger(b, in, 7);
    if ((b & 0b1000_0000) != 0) {
      return readHuffmanAsciiString(in, length);
    } else {
      return readAsciiString(in, length);
    }
  }

  static AsciiString readByteString(final ByteBuf in) throws HpackDecodingException {
    final int b = in.readUnsignedByte();
    final int length = readInteger(b, in, 7);
    if ((b & 0b1000_0000) != 0) {
      return readHuffmanByteString(in, length);
    } else {
      return readByteString(in, length);
    }
  }

  static AsciiString readAsciiString(final ByteBuf in, final int length) throws HpackDecodingException {
    final byte[] bytes = new byte[length];
    in.readBytes(bytes);
    return new AsciiString(bytes, false);
  }

  static AsciiString readByteString(final ByteBuf in, final int length) throws HpackDecodingException {
    final byte[] bytes = new byte[length];
    in.readBytes(bytes);
    return new AsciiString(bytes, false);
  }

  static AsciiString readHuffmanAsciiString(final ByteBuf in, final int length) throws HpackDecodingException {
    final ByteBuf buf = Unpooled.buffer(length * 2);
    Huffman.decode(in, buf, length);
    final AsciiString s = new AsciiString(buf.array(), buf.arrayOffset(), buf.readableBytes(), false);
    return s;
  }

  static AsciiString readHuffmanByteString(final ByteBuf in, final int length) throws HpackDecodingException {
    final ByteBuf buf = Unpooled.buffer(length * 2);
    Huffman.decode(in, buf, length);
    final AsciiString s = new AsciiString(buf.array(), buf.arrayOffset(), buf.readableBytes(), false);
    return s;
  }

  /**
   * https://tools.ietf.org/html/rfc7541#section-6.2.1
   *
   * Figure 6: Literal Header Field with Incremental Indexing -- Indexed Name
   */
  static void writeLiteralHeaderFieldIncrementalIndexing(final ByteBuf out, final int nameIndex,
                                                         final AsciiString value) {
    writeInteger(out, 0b0100_0000, 6, nameIndex);
    writeString(out, value);
  }

  /**
   * https://tools.ietf.org/html/rfc7541#section-6.2.1
   *
   * Figure 7: Literal Header Field with Incremental Indexing -- New Name
   */
  static void writeLiteralHeaderFieldIncrementalIndexingNewName(final ByteBuf out, final AsciiString name,
                                                                final AsciiString value) {
    out.writeByte(0b0100_0000);
    writeString(out, name);
    writeString(out, value);
  }

  /**
   * https://tools.ietf.org/html/rfc7541#section-6.2.2
   *
   * Figure 8: Literal Header Field without Indexing -- Indexed Name
   */
  static void writeLiteralHeaderFieldWithoutIndexing(final ByteBuf out, final int nameIndex,
                                                     final AsciiString value) {
    writeInteger(out, 0b0000_0000, 4, nameIndex);
    writeString(out, value);
  }

  /**
   * https://tools.ietf.org/html/rfc7541#section-6.2.2
   *
   * Figure 9: Literal Header Field without Indexing -- New Name
   */
  static void writeLiteralHeaderFieldWithoutIndexingNewName(final ByteBuf out, final AsciiString name,
                                                            final AsciiString value) {
    out.writeByte(0);
    writeString(out, name);
    writeString(out, value);
  }

  /**
   * https://tools.ietf.org/html/rfc7541#section-6.2.3
   *
   * Figure 10: Literal Header Field Never Indexed -- Indexed Name
   */
  static void writeLiteralHeaderFieldNeverIndexed(final ByteBuf out, final int nameIndex,
                                                  final AsciiString value) {
    writeInteger(out, 0b0001_0000, 4, nameIndex);
    writeString(out, value);
  }

  /**
   * https://tools.ietf.org/html/rfc7541#section-6.2.3
   *
   * Figure 11: Literal Header Field Never Indexed -- New Name
   */
  static void writeLiteralHeaderFieldNeverIndexedNewName(final ByteBuf out, final AsciiString name,
                                                         final AsciiString value) {
    out.writeByte(0b0001_0000);
    writeString(out, name);
    writeString(out, value);
  }
}
