package io.norberg.http2;

import static java.lang.Integer.bitCount;

class HpackDynamicTable {

  private int head;
  private int tail;

  private Http2Header[] table = newTable(16);

  private Http2Header[] newTable(final int length) {
    assert bitCount(length) == 1;
    return new Http2Header[length];
  }

  void addFirst(final Http2Header header) {
    assert length() < table.length;
    table[head] = header;
    head = (head + 1) & (table.length - 1);
    if (head == tail) {
      doubleCapacity();
    }
    assert length() > 0;
  }

  Http2Header removeLast() {
    assert length() > 0;
    final Http2Header header = table[tail];
    table[tail] = null;
    tail = (tail + 1) & (table.length - 1);
    return header;
  }

  Http2Header header(final int index) {
    return table[ix(index)];
  }

  int length() {
    return (head - tail) & (table.length - 1);
  }

  private int ix(final int index) {
    assert index >= 0;
    assert index < length();
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

  void clear() {
    for (int i = head; i < tail; i++) {
      if (i >= table.length) {
        i -= table.length;
      }
      table[i] = null;
    }
    head = tail = 0;
  }
}
