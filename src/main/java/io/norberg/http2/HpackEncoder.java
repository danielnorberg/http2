package io.norberg.http2;

import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.AUTHORITY;
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.METHOD;
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.PATH;
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.SCHEME;
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.STATUS;
import static io.norberg.http2.HpackStaticTable.INDEXED_NAME;
import static io.norberg.http2.HpackStaticTable.isIndexedField;
import static io.norberg.http2.HpackStaticTable.isIndexedName;

import io.netty.buffer.ByteBuf;
import io.netty.util.AsciiString;

class HpackEncoder {

  private final HpackDynamicTable dynamicTable = new HpackDynamicTable();
  private final HpackDynamicTableIndex tableIndex;
  private int tableSize;
  private int maxTableSize;

  HpackEncoder(final int maxTableSize) {
    this.maxTableSize = maxTableSize;
    this.tableIndex = new HpackDynamicTableIndex(dynamicTable);
  }

  void encodeRequest(final ByteBuf out, final AsciiString method, final AsciiString scheme, final AsciiString authority,
      final AsciiString path) {
    writeIndexedHeaderField(out, methodIndex(method), method);
    writeIndexedHeaderField(out, schemeIndex(scheme), scheme);
    writeIndexedHeaderField(out, authorityIndex(authority), authority);
    writeIndexedHeaderField(out, pathIndex(path), path);
  }

  void encodeResponse(final ByteBuf out, final AsciiString status) {
    final int statusIndex = statusIndex(status);
    writeIndexedHeaderField(out, statusIndex, status);
  }

  void encodeHeader(final ByteBuf out, final AsciiString name, final AsciiString value, final boolean sensitive)
      throws HpackEncodingException {
    if (sensitive) {
      encodeSensitiveHeader(out, name, value);
    } else {
      encodeHeader(out, name, value);
    }
  }

  void encodeHeader(final ByteBuf out, final AsciiString name, final AsciiString value)
      throws HpackEncodingException {
    final int index = headerIndex(name, value);
    if (index != 0) {
      if (isIndexedName(index)) {
        final int nameIndex = nameIndex(index);
        // TODO: use name instead?
        indexHeader(name(nameIndex), value);
        Hpack.writeLiteralHeaderFieldIncrementalIndexing(out, nameIndex, value);
      } else {
        Hpack.writeIndexedHeaderField(out, index);
      }
    } else {
      indexHeader(name, value);
      Hpack.writeLiteralHeaderFieldIncrementalIndexingNewName(out, name, value);
    }
  }

  void encodeSensitiveHeader(final ByteBuf out, final AsciiString name, final AsciiString value)
      throws HpackEncodingException {
    final int index = nameIndex(name);
    if (index != 0) {
      final int nameIndex = nameIndex(index);
      Hpack.writeLiteralHeaderFieldNeverIndexed(out, nameIndex, value);
    } else {
      Hpack.writeLiteralHeaderFieldNeverIndexedNewName(out, name, value);
    }
  }

  private int methodIndex(final AsciiString method) {
    final int staticIndex = HpackStaticTable.methodIndex(method);
    if (isIndexedField(staticIndex)) {
      return staticIndex;
    }
    final int dynamicIndex = tableIndex.lookup(METHOD.value(), method);
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
    final int dynamicIndex = tableIndex.lookup(SCHEME.value(), scheme);
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
    final int dynamicIndex = tableIndex.lookup(AUTHORITY.value(), authority);
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
    final int dynamicIndex = tableIndex.lookup(PATH.value(), path);
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
    final int dynamicIndex = tableIndex.lookup(STATUS.value(), status);
    if (dynamicIndex != 0) {
      return dynamicIndex + HpackStaticTable.length();
    }
    return staticIndex;
  }

  private void writeIndexedHeaderField(final ByteBuf out, final int index, final AsciiString value) {
    if (isIndexedName(index)) {
      final int nameIndex = nameIndex(index);
      indexHeader(name(nameIndex), value);
      Hpack.writeLiteralHeaderFieldIncrementalIndexing(out, nameIndex, value);
    } else {
      Hpack.writeIndexedHeaderField(out, index);
    }
  }

  private int dynamicHeaderIndex(final AsciiString name, final AsciiString value) {
    final int headerIndex = tableIndex.lookup(name, value);
    if (headerIndex != 0) {
      return headerIndex + HpackStaticTable.length();
    }
    final int nameIndex = tableIndex.lookup(name);
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
        tableIndex.clear();
        return;
      }
      while (newTableSize > maxTableSize) {
        final int index = dynamicTable.length();
        final Http2Header removed = dynamicTable.removeLast();
        tableIndex.remove(removed);
        newTableSize -= removed.size();
      }
    }
    tableSize = newTableSize;
    dynamicTable.addFirst(header);
    tableIndex.insert(header);
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
    return tableIndex.lookup(name) + HpackStaticTable.length();
  }

  int tableLength() {
    return dynamicTable.length();
  }

  int maxTableSize() {
    return maxTableSize;
  }

  void setMaxTableSize(int maxTableSize) {
    while (tableSize > maxTableSize) {
      final Http2Header removed = dynamicTable.removeLast();
      tableSize -= removed.size();
    }
    this.maxTableSize = maxTableSize;
  }
}
