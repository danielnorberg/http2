package io.norberg.h2client;

import static java.lang.Integer.bitCount;

class HpackDynamicTable {

  private int capacity;
  private int size;

  private int head;
  private int tail;

  private Http2Header[] table;

  HpackDynamicTable(final int capacity) {
    this.capacity = capacity;
    this.table = newTable(16);
  }

  private Http2Header[] newTable(final int length) {
    assert bitCount(length) == 1;
    return new Http2Header[length];
  }

  void addFirst(final Http2Header header) {
    final int headerSize = size(header);
    int newSize = size + headerSize;
    if (newSize > capacity) {
      if (headerSize > capacity) {
        clear();
        assert entries() == 0;
        return;
      }
      while (newSize > capacity) {
        removeLast();
      }
    }
    assert entries() < table.length;
    table[head] = header;
    head = (head + 1) & (table.length - 1);
    if (head == tail) {
      doubleCapacity();
    }
    size += headerSize;
    assert entries() > 0;
  }

  Http2Header removeLast() {
    assert entries() > 0;
    final Http2Header header = table[tail];
    tail = (tail + 1) & (table.length - 1);
    table[tail] = null;
    size -= size(header);
    return header;
  }

  Http2Header header(final int index) {
    return table[ix(index)];
  }

  int entries() {
    return (head - tail) & (table.length - 1);
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
    final int ix = (head - index - 1) & (table.length - 1);
    return ix;
  }

  private void doubleCapacity() {
    assert head == tail;
    final Http2Header[] newTable = newTable(table.length * 2);
    System.arraycopy(table, head, newTable, 0, table.length - head);
    System.arraycopy(table, 0, newTable, table.length - head, tail);
    head = table.length;
    tail = 0;
    table = newTable;
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

  void capacity(final int capacity) {
    this.capacity = capacity;
    if (size > capacity) {
      removeLast();
    }
  }

  int capacity() {
    return capacity;
  }
}
