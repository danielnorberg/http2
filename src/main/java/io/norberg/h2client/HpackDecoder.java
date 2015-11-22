package io.norberg.h2client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.AsciiString;

class HpackDecoder {

  private final HpackDynamicTable dynamicTable;

  public HpackDecoder(final int maxTableSize) {
    this.dynamicTable = new HpackDynamicTable(maxTableSize);
  }

  public void decode(final ByteBuf in, final Listener listener) throws HpackDecodingException {
    while (in.isReadable()) {
      final int b = in.readUnsignedByte();
      final Http2Header header;
      if ((b & 0b1000_0000) != 0) {
        // 6.1 Indexed Header Field Representation
        final int index = readInteger(b, in, 7);
        header = header(index);
      } else if ((b & 0b0100_0000) != 0) {
        // 6.2.1 Literal Header Field with Incremental Indexing
        if (b != 0b0100_0000) {
          // Literal Header Field with Incremental Indexing — Indexed Name
          header = readLiteralHeaderFieldIndexedName(b, in, 6, false);
        } else {
          // Literal Header Field with Incremental Indexing — New Name
          header = readLiteralHeaderFieldNewName(in, false);
        }
        dynamicTable.addFirst(header);
      } else if ((b & 0b0010_0000) != 0) {
        final int maxSize = readInteger(b, in, 5);
        dynamicTable.capacity(maxSize);
        continue;
      } else if ((b & 0b0001_0000) != 0) {
        // 6.2.3 Literal Header Field Never Indexed
        if (b != 0b0001_0000) {
          // Literal Header Field Never Indexed — Indexed Name
          header = readLiteralHeaderFieldIndexedName(b, in, 4, true);
        } else {
          // Literal Header Field Never Indexed — New Name
          header = readLiteralHeaderFieldNewName(in, true);
        }
      } else {
        // 6.2.2 Literal Header Field without Indexing
        if (b != 0) {
          // Literal Header Field without Indexing — Indexed Name
          header = readLiteralHeaderFieldIndexedName(b, in, 7, false);
        } else {
          // Literal Header Field without Indexing — New Name
          header = readLiteralHeaderFieldNewName(in, false);
        }
      }
      listener.header(header);
    }
  }

  private Http2Header readLiteralHeaderFieldIndexedName(final int b, final ByteBuf in, final int n,
                                                        final boolean sensitive)
      throws HpackDecodingException {
    final int index = readInteger(b, in, n);
    final Http2Header template = header(index);
    final AsciiString name = template.name();
    final AsciiString value = readByteString(in);
    return Http2Header.of(name, value, sensitive);
  }

  private Http2Header readLiteralHeaderFieldNewName(final ByteBuf in, final boolean sensitive)
      throws HpackDecodingException {
    final AsciiString name = readAsciiString(in);
    final AsciiString value = readByteString(in);
    return Http2Header.of(name, value, sensitive);
  }

  private Http2Header header(final int index) throws HpackDecodingException {
    final Http2Header header;
    if (index <= 0) {
      throw new HpackDecodingException();
    }
    if (isStatic(index)) {
      header = StaticHpackTable.header(index);
    } else {
      header = dynamicHeader(index);
    }
    return header;
  }

  private boolean isStatic(final int index) {
    return index <= StaticHpackTable.length();
  }

  private Http2Header dynamicHeader(final int index) throws HpackDecodingException {
    final Http2Header header;
    final int dynamicIndex = index - StaticHpackTable.length() - 1;
    if (dynamicIndex >= dynamicTable.size()) {
      throw new HpackDecodingException();
    }
    header = dynamicTable.header(dynamicIndex);
    return header;
  }

  private AsciiString readAsciiString(final ByteBuf in) throws HpackDecodingException {
    final int b = in.readUnsignedByte();
    final int length = readInteger(b, in, 7);
    if ((b & 0b1000_0000) != 0) {
      return readHuffmanAsciiString(in, length);
    } else {
      return readAsciiString(in, length);
    }
  }

  private AsciiString readByteString(final ByteBuf in) throws HpackDecodingException {
    final int b = in.readUnsignedByte();
    final int length = readInteger(b, in, 7);
    if ((b & 0b1000_0000) != 0) {
      return readHuffmanByteString(in, length);
    } else {
      return readByteString(in, length);
    }
  }

  private AsciiString readAsciiString(final ByteBuf in, final int length) throws HpackDecodingException {
    final byte[] bytes = new byte[length];
    checkReadable(in, length);
    in.readBytes(bytes);
    return new AsciiString(bytes, false);
  }

  private AsciiString readByteString(final ByteBuf in, final int length) throws HpackDecodingException {
    final byte[] bytes = new byte[length];
    checkReadable(in, length);
    in.readBytes(bytes);
    return new AsciiString(bytes, false);
  }

  private AsciiString readHuffmanAsciiString(final ByteBuf in, final int length) throws HpackDecodingException {
    final ByteBuf buf = Unpooled.buffer(length * 2);
    checkReadable(in, length);
    Huffman.decode(in, buf, length);
    final AsciiString s = new AsciiString(buf.array(), buf.arrayOffset(), buf.readableBytes(), false);
    return s;
  }

  private AsciiString readHuffmanByteString(final ByteBuf in, final int length) throws HpackDecodingException {
    final ByteBuf buf = Unpooled.buffer(length * 2);
    checkReadable(in, length);
    Huffman.decode(in, buf, length);
    final AsciiString s = new AsciiString(buf.array(), buf.arrayOffset(), buf.readableBytes(), false);
    return s;
  }

  private int readInteger(int i, final ByteBuf buf, final int n) throws HpackDecodingException {
    final int maskBits = 8 - n;
    final int nMask = (0xFF >> maskBits);
    i &= nMask;
    if (i < nMask) {
      return i;
    }

    int m = 0;
    int b;
    do {
      requireReadable(buf);
      b = buf.readUnsignedByte();
      i += (b & 0x7F) << m;
      m = m + 7;
    } while ((b & 0x80) == 0x80);
    return i;
  }

  private void requireReadable(final ByteBuf buf) throws HpackDecodingException {
    if (!buf.isReadable()) {
      throw new HpackDecodingException();
    }
  }

  private void checkReadable(final ByteBuf buf, final int length) throws HpackDecodingException {
    if (buf.readableBytes() < length) {
      throw new HpackDecodingException();
    }
  }

  public int dynamicTableSize() {
    return dynamicTable.capacity();
  }

  public int dynamicTableLength() {
    return dynamicTable.length();
  }

  interface Listener {

    void header(Http2Header header);
  }

}
