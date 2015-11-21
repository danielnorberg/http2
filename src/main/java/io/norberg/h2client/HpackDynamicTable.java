package io.norberg.h2client;

import static java.lang.Integer.bitCount;

class HpackDynamicTable {

  private int maxSize;
  private int size;

  private int head;
  private int tail;

  private Http2Header[] table;

  HpackDynamicTable(final int maxSize) {
    this.maxSize = maxSize;
    this.table = newTable(16);
  }

  private Http2Header[] newTable(final int length) {
    assert bitCount(length) == 1;
    return new Http2Header[length];
  }

  void addFirst(final Http2Header header) {
    final int headerSize = size(header);
    int newSize = size + headerSize;
    if (newSize > maxSize) {
      if (headerSize > maxSize) {
        clear();
        return;
      }
      while (newSize > maxSize) {
        removeLast();
      }
    }
    ensureCapacity();
    assert entries() < table.length;
    head = (head - 1) & (table.length - 1);
    table[head] = header;
    size += headerSize;
  }

  Http2Header removeLast() {
    assert entries() > 0;
    tail = (tail - 1) & (table.length - 1);
    final Http2Header header = table[tail];
    table[tail] = null;
    size -= size(header);
    return header;
  }

  Http2Header header(final int index) {
    return table[ix(index)];
  }

  int entries() {
    return (tail - head) & (table.length - 1);
  }

  int size() {
    return size;
  }

  private static int size(final Http2Header header) {
    return header.name().length() + header.value().length() + 32;
  }

  private int ix(final int index) {
    assert index >= 0;
    assert index < entries();
    final int ix = (index + head) & (table.length - 1);
    return ix;
  }

  private void ensureCapacity() {
    if (entries() == table.length) {
      final Http2Header[] newTable = newTable(table.length * 2);
      if (head > tail) {
        System.arraycopy(table, head, newTable, 0, table.length - head);
        System.arraycopy(table, 0, newTable, table.length - head, tail);
      } else {
        System.arraycopy(table, 0, newTable, table.length - head, tail);
      }
      table = newTable;
      tail = entries();
      head = 0;
    }
  }

  private void clear() {
    for (int i = head; i < tail; i++) {
      if (i >= table.length) {
        i -= table.length;
      }
      table[i] = null;
    }
    head = tail = 0;
  }
}
