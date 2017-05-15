package io.norberg.http2;

import static io.norberg.http2.Hpack.readAsciiString;
import static io.norberg.http2.Hpack.readInteger;

import io.netty.buffer.ByteBuf;
import io.netty.util.AsciiString;

class HpackDecoder {

  private final HpackDynamicTable dynamicTable = new HpackDynamicTable();

  private int tableSize;
  private int maxTableSize;

  HpackDecoder(final int maxTableSize) {
    this.maxTableSize = maxTableSize;
  }

  void decode(final ByteBuf in, final Listener listener) throws Http2Exception {
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
        addHeader(header);
      } else if ((b & 0b0010_0000) != 0) {
        final int maxSize = readInteger(b, in, 5);
        setMaxTableSize(maxSize);
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
          header = readLiteralHeaderFieldIndexedName(b, in, 4, false);
        } else {
          // Literal Header Field without Indexing — New Name
          header = readLiteralHeaderFieldNewName(in, false);
        }
      }
      listener.header(header);
    }
  }

  private void setMaxTableSize(final int maxTableSize) {
    while (tableSize > maxTableSize) {
      final Http2Header removed = dynamicTable.removeLast();
      tableSize -= removed.size();
    }
    this.maxTableSize = maxTableSize;
  }

  private void addHeader(final Http2Header header) {
    final int headerSize = header.size();
    int newTableSize = tableSize + headerSize;
    if (newTableSize > maxTableSize) {
      if (headerSize > maxTableSize) {
        tableSize = 0;
        dynamicTable.clear();
        return;
      }
      while (newTableSize > maxTableSize) {
        final Http2Header removed = dynamicTable.removeLast();
        newTableSize -= removed.size();
      }
    }
    tableSize = newTableSize;
    dynamicTable.addFirst(header);
  }

  private Http2Header readLiteralHeaderFieldIndexedName(final int b, final ByteBuf in, final int n,
      final boolean sensitive)
      throws HpackDecodingException {
    final int index = readInteger(b, in, n);
    final Http2Header template = header(index);
    final AsciiString name = template.name();
    final AsciiString value = readAsciiString(in);
    return Http2Header.of(name, value, sensitive);
  }

  static Http2Header readLiteralHeaderFieldNewName(final ByteBuf in, final boolean sensitive)
      throws HpackDecodingException {
    final AsciiString name = readAsciiString(in);
    final AsciiString value = readAsciiString(in);
    return Http2Header.of(name, value, sensitive);
  }

  private Http2Header header(final int index) throws HpackDecodingException {
    final Http2Header header;
    if (index <= 0) {
      throw new HpackDecodingException();
    }
    if (HpackStaticTable.isStaticIndex(index)) {
      header = HpackStaticTable.header(index);
    } else {
      header = dynamicHeader(index);
    }
    return header;
  }

  private Http2Header dynamicHeader(final int index) throws HpackDecodingException {
    final Http2Header header;
    final int dynamicIndex = index - HpackStaticTable.length() - 1;
    if (dynamicIndex >= tableSize) {
      throw new HpackDecodingException();
    }
    header = dynamicTable.header(dynamicIndex);
    return header;
  }

  public int maxTableSize() {
    return maxTableSize;
  }

  public int tableSize() {
    return tableSize;
  }

  public int tableLength() {
    return dynamicTable.length();
  }

  interface Listener {

    void header(Http2Header header) throws Http2Exception;
  }

}
