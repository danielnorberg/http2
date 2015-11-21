package io.norberg.h2client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.AsciiString;
import io.netty.util.ByteString;

class HpackDecoder {

  private static final int INDEXED_HEADER_FIELD = 0x80;
  private static final int LITERAL_HEADER_FIELD_INCREMENTAL_INDEXING = 0x40;
  private static final int HUFFMAN_ENCODED = 0x80;

  private final HpackDynamicTable dynamicTable;

  public HpackDecoder(final int maxTableSize) {
    this.dynamicTable = new HpackDynamicTable(maxTableSize);
  }

  public void decode(final ByteBuf in, final Listener listener) throws HpackDecodingException {
    while (true) {
      if (!in.isReadable()) {
        break;
      }

      final int b = in.readUnsignedByte();
      if ((b & INDEXED_HEADER_FIELD) != 0) {
        // 6.1 Indexed Header Field Representation
        readIndexedHeaderField(b, in, listener);
      } else if ((b & LITERAL_HEADER_FIELD_INCREMENTAL_INDEXING) != 0) {
        // 6.2.1 Literal Header Field with Incremental Indexing
        if (b != LITERAL_HEADER_FIELD_INCREMENTAL_INDEXING) {
          // Literal Header Field with Incremental Indexing — Indexed Name
          readLiteralHeaderFieldIndexingIndexedName(b, in, listener);
        } else {
          // Literal Header Field with Incremental Indexing — New Name
          readLiteralHeaderFieldIndexingNewName(b, in, listener);
        }
      } else {
        // 6.2.2 Literal Header Field without Indexing
        if (b != 0) {
          // Literal Header Field without Indexing — Indexed Name
          readLiteralHeaderFieldWithoutIndexingIndexedName(b, in, listener);
        } else {
          // Literal Header Field without Indexing — New Name
          readLiteralHeaderFieldWithoutIndexingNewName(b, in, listener);
        }
      }
    }
  }

  private void readLiteralHeaderFieldWithoutIndexingIndexedName(final int b, final ByteBuf in,
                                                                final Listener listener) {
    final int index = readInteger(b, in, 7);
    if (index >= StaticHpackTable.length()) {
      throw new IllegalArgumentException("no dynamic table yet");
    }
    final Http2Header template = StaticHpackTable.header(index);
    final AsciiString name = template.name();
    final ByteString value = readByteString(in);
    final Http2Header header = Http2Header.of(name, value);
    listener.header(header);
  }

  private void readLiteralHeaderFieldWithoutIndexingNewName(final int b, final ByteBuf in,
                                                            final Listener listener) {
    final AsciiString name = readAsciiString(in);
    final ByteString value = readByteString(in);
    final Http2Header header = Http2Header.of(name, value);
    listener.header(header);
  }

  private void readLiteralHeaderFieldIndexingNewName(final int b, final ByteBuf in, final Listener listener) {
    final AsciiString name = readAsciiString(in);
    final ByteString value = readByteString(in);
    final Http2Header header = Http2Header.of(name, value);
    dynamicTable.addFirst(header);
    listener.header(header);
  }

  private void readLiteralHeaderFieldIndexingIndexedName(final int b, final ByteBuf in, final Listener listener)
      throws HpackDecodingException {
    final int index = readInteger(b, in, 6);
    final Http2Header template = header(index);
    final AsciiString name = template.name();
    final ByteString value = readByteString(in);
    final Http2Header header = Http2Header.of(name, value);
    dynamicTable.addFirst(header);
    listener.header(header);
  }

  private void readIndexedHeaderField(final int b, final ByteBuf in, final Listener listener)
      throws HpackDecodingException {
    final int index = readInteger(b, in, 7);
    final Http2Header header = header(index);
    listener.header(header);
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

  private AsciiString readAsciiString(final ByteBuf in) {
    final int b = in.readUnsignedByte();
    final int length = readInteger(b, in, 7);
    if ((b & HUFFMAN_ENCODED) != 0) {
      return readHuffmanAsciiString(in, length);
    } else {
      return readAsciiString(in, length);
    }
  }

  private ByteString readByteString(final ByteBuf in) {
    final int b = in.readUnsignedByte();
    final int length = readInteger(b, in, 7);
    if ((b & HUFFMAN_ENCODED) != 0) {
      return readHuffmanByteString(in, length);
    } else {
      return readByteString(in, length);
    }
  }

  private AsciiString readAsciiString(final ByteBuf in, final int length) {
    final byte[] bytes = new byte[length];
    in.readBytes(bytes);
    return new AsciiString(bytes, false);
  }

  private ByteString readByteString(final ByteBuf in, final int length) {
    final byte[] bytes = new byte[length];
    in.readBytes(bytes);
    return new ByteString(bytes, false);
  }

  private AsciiString readHuffmanAsciiString(final ByteBuf in, final int length) {
    final ByteBuf buf = Unpooled.buffer(length * 2);
    Huffman.decode(in, buf, length);
    final AsciiString s = new AsciiString(buf.array(), buf.arrayOffset(), buf.readableBytes(), false);
    return s;
  }

  private ByteString readHuffmanByteString(final ByteBuf in, final int length) {
    final ByteBuf buf = Unpooled.buffer(length * 2);
    Huffman.decode(in, buf, length);
    final ByteString s = new ByteString(buf.array(), buf.arrayOffset(), buf.readableBytes(), false);
    return s;
  }

  private int readInteger(int i, final ByteBuf buf, final int n) {
    final int maskBits = 8 - n;
    final int nMask = (0xFF >> maskBits);
    i &= nMask;
    if (i < nMask) {
      return i;
    }

    int m = 0;
    int b;
    do {
      b = buf.readUnsignedByte();
      i += (b & 0x7F) << m;
      m = m + 7;
    } while ((b & 0x80) == 0x80);
    return i;
  }

  interface Listener {

    void header(final Http2Header header);
  }

}
