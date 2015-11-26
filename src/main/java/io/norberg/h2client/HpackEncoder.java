package io.norberg.h2client;

import io.netty.buffer.ByteBuf;
import io.netty.util.AsciiString;

import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.AUTHORITY;
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.METHOD;
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.PATH;
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.SCHEME;
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.STATUS;
import static io.norberg.h2client.HpackStaticTable.INDEXED_NAME;
import static io.norberg.h2client.HpackStaticTable.isIndexedField;
import static io.norberg.h2client.HpackStaticTable.isIndexedName;

class HpackEncoder {

  enum Indexing {
    INCREMENTAL,
    NONE,
    NEVER
  }

  private final HpackDynamicTable dynamicTable = new HpackDynamicTable();
  private final HpackDynamicTableIndex dynamicTableIndex;
  private int tableSize;
  private int maxTableSize;
  private int headerIndex;

  public HpackEncoder(final int maxTableSize) {
    this.maxTableSize = maxTableSize;
    this.dynamicTableIndex = new HpackDynamicTableIndex(16);
  }

  void encodeRequest(final ByteBuf out, final AsciiString method, final AsciiString scheme, final AsciiString authority,
                     final AsciiString path) {
    final int methodIndex = methodIndex(method);
    final int schemeIndex = schemeIndex(scheme);
    final int authorityIndex = authorityIndex(authority);
    final int pathIndex = pathIndex(path);
    writeIndexedHeaderField(out, methodIndex, method, false);
    writeIndexedHeaderField(out, schemeIndex, scheme, false);
    writeIndexedHeaderField(out, authorityIndex, authority, false);
    writeIndexedHeaderField(out, pathIndex, path, false);
  }

  void encodeResponse(final ByteBuf out, final AsciiString status) {
    final int statusIndex = statusIndex(status);
    writeIndexedHeaderField(out, statusIndex, status, false);
  }

  void encodeHeader(final ByteBuf out, final AsciiString name, final AsciiString value, final boolean sensitive)
      throws HpackEncodingException {
    if (sensitive) {
      final int index = nameIndex(name);
      if (index != 0) {
        writeIndexedHeaderField(out, index, value, true);
      } else {
        writeNewHeaderField(out, name, value, true);
      }
    } else {
      final int index = headerIndex(name, value);
      if (index != 0) {
        writeIndexedHeaderField(out, index, value, false);
      } else {
        writeNewHeaderField(out, name, value, false);
      }
    }
  }

  private int methodIndex(final AsciiString method) {
    final int staticIndex = HpackStaticTable.methodIndex(method);
    if (isIndexedField(staticIndex)) {
      return staticIndex;
    }
    final int dynamicIndex = dynamicTableIndex.index(METHOD.value(), method);
    if (dynamicIndex != 0) {
      return dynamicIndex + HpackStaticTable.length();
    }
    return staticIndex;
  }

  private int schemeIndex(final AsciiString scheme) {
    final int staticIndex = HpackStaticTable.schemeIndex(scheme);
    if (isIndexedField(staticIndex)) {
      return staticIndex;
    }
    final int dynamicIndex = dynamicTableIndex.index(SCHEME.value(), scheme);
    if (dynamicIndex != 0) {
      return dynamicIndex + HpackStaticTable.length();
    }
    return staticIndex;
  }

  private int authorityIndex(final AsciiString authority) {
    final int staticIndex = HpackStaticTable.authorityIndex();
    if (isIndexedField(staticIndex)) {
      return staticIndex;
    }
    final int dynamicIndex = dynamicTableIndex.index(AUTHORITY.value(), authority);
    if (dynamicIndex != 0) {
      return dynamicIndex + HpackStaticTable.length();
    }
    return staticIndex;
  }

  private int pathIndex(final AsciiString path) {
    final int staticIndex = HpackStaticTable.pathIndex(path);
    if (isIndexedField(staticIndex)) {
      return staticIndex;
    }
    final int dynamicIndex = dynamicTableIndex.index(PATH.value(), path);
    if (dynamicIndex != 0) {
      return dynamicIndex + HpackStaticTable.length();
    }
    return staticIndex;
  }

  private int statusIndex(final AsciiString status) {
    final int staticIndex = HpackStaticTable.statusIndex(status);
    if (isIndexedField(staticIndex)) {
      return staticIndex;
    }
    final int dynamicIndex = dynamicTableIndex.index(STATUS.value(), status);
    if (dynamicIndex != 0) {
      return dynamicIndex + HpackStaticTable.length();
    }
    return staticIndex;
  }

  private void writeIndexedHeaderField(final ByteBuf out, final int index, final AsciiString value,
                                       final boolean sensitive) {
    if (isIndexedName(index) || sensitive) {
      final int nameIndex = nameIndex(index);
      final int mask;
      final int n;
      if (sensitive) {
        mask = 0b0001_0000;
        n = 4;
      } else {
        mask = 0b0100_0000;
        n = 6;
        indexHeader(name(nameIndex), value);
      }
      writeInteger(out, mask, n, nameIndex);
      writeString(out, value);
    } else {
      writeInteger(out, 0b1000_0000, 7, index);
    }
  }

  private int dynamicHeaderIndex(final AsciiString name, final AsciiString value) {
    final int headerIndex = dynamicTableIndex.index(name, value);
    if (headerIndex != 0) {
      return headerIndex + HpackStaticTable.length();
    }
    final int nameIndex = dynamicTableIndex.index(name);
    if (nameIndex != 0) {
      return nameIndex + HpackStaticTable.length() | INDEXED_NAME;
    }
    return 0;
  }

  private void indexHeader(final AsciiString name, final AsciiString value) {
    // TODO: eliminate header object garbage
    final Http2Header header = Http2Header.of(name, value);
    final int headerSize = header.size();
    int newTableSize = tableSize + headerSize;
    if (newTableSize > maxTableSize) {
      if (headerSize > maxTableSize) {
        tableSize = 0;
        dynamicTable.clear();
        dynamicTableIndex.clear();
        return;
      }
      while (newTableSize > maxTableSize) {
        final int index = dynamicTable.length();
        final Http2Header removed = dynamicTable.removeLast();
        dynamicTableIndex.remove(removed, index);
        newTableSize -= removed.size();
      }
    }
    tableSize = newTableSize;
    dynamicTable.addFirst(header);
    dynamicTableIndex.add(header);
  }

  private static int nameIndex(final int index) {
    return HpackStaticTable.nameIndex(index);
  }

  private AsciiString name(final int index) {
    return header(index & (~INDEXED_NAME)).name();
  }

  private Http2Header header(final int index) {
    if (index <= HpackStaticTable.length()) {
      return HpackStaticTable.header(index);
    }
    return dynamicTable.header(index - HpackStaticTable.length() - 1);
  }

  private void writeNewHeaderField(final ByteBuf out, final AsciiString name, final AsciiString value,
                                   final boolean sensitive) {
    if (sensitive) {
      out.writeByte(0b0001_0000);
    } else {
      out.writeByte(0b0100_0000);
      indexHeader(name, value);
    }
    writeString(out, name);
    writeString(out, value);
  }

  private int headerIndex(final AsciiString name, final AsciiString value) throws HpackEncodingException {
    final int staticIndex = HpackStaticTable.headerIndex(name, value);
    if (isIndexedField(staticIndex)) {
      return staticIndex;
    }

    final int dynamicIndex = dynamicHeaderIndex(name, value);
    if (isIndexedField(dynamicIndex)) {
      return dynamicIndex;
    }

    if (isIndexedName(staticIndex)) {
      return staticIndex;
    }

    return dynamicIndex;
  }

  private int nameIndex(final AsciiString name) throws HpackEncodingException {
    final int staticIndex = HpackStaticTable.nameIndex(name);
    if (staticIndex != 0) {
      return staticIndex;
    }
    return dynamicTableIndex.index(name) + HpackStaticTable.length();
  }

  private void writeString(final ByteBuf buf, final AsciiString s) {
    final int encodedLength = Huffman.encodedLength(s);
    if (encodedLength < s.length()) {
      writeHuffmanString(buf, s, encodedLength);
    } else {
      writeRawString(buf, s);
    }
  }

  private void writeRawString(final ByteBuf buf, final AsciiString s) {
    writeInteger(buf, 0, 7, s.length());
    buf.writeBytes(s.array(), s.arrayOffset(), s.length());
  }

  private void writeHuffmanString(final ByteBuf buf, final AsciiString s, final int encodedLength) {
    writeInteger(buf, 0x80, 7, encodedLength);
    Huffman.encode(buf, s);
  }

  private void writeInteger(final ByteBuf buf, final int mask, final int n, int i) {
    final int maskBits = 8 - n;
    final int nMask = (0xFF >> maskBits);
    if (i < nMask) {
      buf.writeByte(mask | i);
      return;
    }

    buf.writeByte(mask | nMask);
    i = i - nMask;
    while (i >= 0x80) {
      buf.writeByte((i & 0x7F) + 0x80);
      i >>= 7;
    }
    buf.writeByte(i);
  }

  public int dynamicTableLength() {
    return dynamicTable.length();
  }

}
