package io.norberg.http2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.netty.util.AsciiString;

final class HpackDynamicTableIndex3 {

  private final float loadFactor = 0.5f;

  // TODO: load factor - dynamic capacity
  private static final int MAX_CAPACITY = 1 << 30;
  private static final int INITIAL_CAPACITY = 32;
  private int growthThreshold;
  private int rehashMask;

  // header seq | header hash
  private long table[] = new long[INITIAL_CAPACITY];

  private int seq = 0;

  private final HpackDynamicTable headerTable;

  HpackDynamicTableIndex3(final HpackDynamicTable headerTable) {
    this.headerTable = headerTable;
    this.growthThreshold = (int) (loadFactor * table.length / 2);
    this.rehashMask = table.length - 1;
  }

  void insert(Http2Header header) {
    seq++;
    if (headerTable.length() > growthThreshold) {
      rehash(table.length << 1);
      return;
    } else if ((seq & rehashMask) == rehashMask) {
//      rehash(table.length);
//      System.out.println("Before compact");
//      inspect().forEach(System.out::println);
      compact();
//      System.out.println("After compact");
//      inspect().forEach(System.out::println);
    }
    insert(table, header, hash(header), seq, seq, headerTable, false);
    insert(table, header.name(), hash(header.name()), seq, seq, headerTable, true);
  }

  private void compact() {
    compact(table, seq, headerTable.length());
  }

  static void compact(final long[] table, final int tableSeq, final int count) {
    assert count != 0;

    final int capacity = table.length;
    final int mask = capacity - 1;

    int insertPos = 0;
    int probePos = 0;

    final int n = table.length + (table.length >> 2);
    for (int i = 0; i < n; i++) {
      final long probeEntry = table[probePos];

      // Is the probed bucket unused?
      if (probeEntry == 0) {
        probePos = next(probePos, mask);
        continue;
      }

      final int probeSeq = entrySeq(probeEntry);
      final int probeTableIndex = entryTableIndex(tableSeq, probeSeq);
      final int probeHash = entryHash(probeEntry);
      final boolean probeExpired = entryExpired(probeTableIndex, count);

      // Is the probed entry expired?
      if (probeExpired) {
        // Keep probing for the stop-bucket
        probePos = next(probePos, mask);
        continue;
      }

      // Do we have any empty buckets to fill?
      if (insertPos == probePos) {
        insertPos = next(probePos, mask);
        probePos = next(probePos, mask);
        continue;
      }

      final int probeIB = ib(probeHash, mask);
      final int probeDIB = dib(probeIB, probePos, capacity, mask);

      // Is the probed entry already optimally located?
      if (probeDIB == 0) {
        clear(table, insertPos, probePos);
        insertPos = next(probePos, mask);
        probePos = next(probePos, mask);
        continue;
      }

      // Is the IB of the probed entry after the insertion bucket?
      final int probeInsertDIB = dib(probeIB, insertPos, capacity, mask);
      if (probeInsertDIB > probeDIB) {
        // Yes, move the probed entry to its IB.
        clear(table, insertPos, probeIB);
        table[probeIB] = probeEntry;
        insertPos = next(probeIB, mask);
        probePos = next(probePos, mask);
        continue;
      } else {
        // Move the probed entry to the insertion bucket
        table[insertPos] = probeEntry;
        insertPos = next(insertPos, mask);
        probePos = next(probePos, mask);
        continue;
      }
    }
  }

//  private static void compact(final long[] table, final int seq, final int count) {
//    final int capacity = table.length;
//    final int mask = capacity - 1;
//
//    int i = 0;
//    for (; i < table.length; i++) {
//      final long entry = table[i];
//      if (entry == 0) {
//        break;
//      }
//      final int entryHash = entryHash(entry);
//      final int entryIB = ib(entryHash, mask);
//      final int entryDIB = dib(entryIB, i, capacity, mask);
//      final int entrySeq = entrySeq(entry);
//      final int entryTableIndex = entryTableIndex(seq, entrySeq);
//      final boolean entryExpired = entryExpired(entryTableIndex, count);
//      if (entryDIB == 0) {
//        break;
//      }
//    }
//
//    assert i < table.length;
//
//    final int end = i;
//    int insert = i;
//    i = next(i, mask);
//    while (true) {
//      if (i == end) {
//        clear(table, insert, i);
//        return;
//      }
//
//      final long entry = table[i];
//      if (entry == 0) {
//        i = next(i, mask);
//        continue;
//      }
//
//      final int entryHash = entryHash(entry);
//      final int entrySeq = entrySeq(entry);
//      final int entryTableIndex = entryTableIndex(seq, entrySeq);
//      final boolean entryExpired = entryExpired(entryTableIndex, count);
//      if (entryExpired) {
//        i = next(i, mask);
//        continue;
//      }
//
//      if (insert == i) {
//        insert = next(insert, mask);
//        i = insert;
//        continue;
//      }
//
//      final int entryIB = ib(entryHash, mask);
//      final int entryDIB = dib(entryIB, i, capacity, mask);
//      if (entryDIB == 0) {
//        clear(table, insert, i);
//        insert = next(i, mask);
//        i = insert;
//        continue;
//      }
//
//      final int entryInsertDIB = dib(entryIB, insert, capacity, mask);
//      if (entryInsertDIB < entryDIB) {
//        table[insert] = entry;
//        insert = next(insert, mask);
//        i = next(i, mask);
//        continue;
//      } else {
//        clear(table, insert, entryIB);
//        table[entryIB] = entry;
//        insert = next(entryIB, mask);
//        i = next(i, mask);
//        clear(table, next(entryIB, mask), i);
//        continue;
//      }
//    }
//  }

  private static void clear(final long[] table, final int start, final int end) {
    if (end < start) {
      Arrays.fill(table, start, table.length, 0);
      Arrays.fill(table, 0, end, 0);
    } else {
      Arrays.fill(table, start, end, 0);
    }
  }

  private void rehash(int newCapacity) {
    if (newCapacity >= MAX_CAPACITY) {
      throw new OutOfMemoryError();
    }
    if (newCapacity == table.length) {
      Arrays.fill(table, 0);
    } else {
      table = new long[newCapacity];
      growthThreshold = (int) (loadFactor * table.length / 2);
      rehashMask = table.length - 1;
    }
    final int insertSeq = this.seq;
    int seq = insertSeq - headerTable.length();
    for (int i = headerTable.length() - 1; i >= 0; i--) {
      final Http2Header header = headerTable.header(i);
      seq++;
      insert(table, header, hash(header), seq, insertSeq, headerTable, false);
      insert(table, header.name(), hash(header.name()), seq, insertSeq, headerTable, true);
    }
  }

  static void insert(final long[] table, final Object insertValue, final int insertHash, final int insertSeq,
                     final int tableSeq, final HpackDynamicTable headerTable,
                     boolean name) {
    final int count = headerTable.length();
    final int capacity = table.length;
    final int mask = capacity - 1;
    final int insertIB = ib(insertHash, mask);

    Object value = insertValue;
    int seq = insertSeq;
    int hash = insertHash;
    int dist = 0;
    int pos = insertIB;

    while (true) {
      if (dist == capacity) {
        throw new AssertionError();
      }

      final long probeEntry = table[pos];

      // Is the probed bucket unused?
      if (probeEntry == 0) {
        // We're done. Store our entry and bail.
        table[pos] = entry(seq, hash);
        return;
      }

      final int entrySeq = entrySeq(probeEntry);
      final int entryTableIndex = entryTableIndex(tableSeq, entrySeq);
      final int entryHash = entryHash(probeEntry);
      final int entryIB = ib(entryHash, mask);
      final int entryDIB = dib(entryIB, pos, capacity, mask);

      // Is the entry identical?
      if (hash == entryHash && value != null) {
        final Object probeValue;
        final boolean entryExpired = entryExpired(entryTableIndex, count);
        if (entryExpired) {
          table[pos] = entry(seq, hash);
          return;
        }
        final Http2Header probeHeader = headerTable.header(entryTableIndex);
        if (name) {
          probeValue = probeHeader.name();
        } else {
          probeValue = probeHeader;
        }
        if (value.equals(probeValue)) {
          table[pos] = entry(seq, hash);
          return;
        }
      }

      // Displace the current entry?
      if (dist > entryDIB) {
        table[pos] = entry(seq, hash);
        final boolean entryExpired = entryExpired(entryTableIndex, count);
        if (entryExpired) {
          return;
        }
        seq = entrySeq;
        hash = entryHash;
        dist = entryDIB;
        value = null;
      }

      dist++;
      pos = next(pos, mask);
    }
  }

  static int next(final int i, final int mask) {
    return (i + 1) & mask;
  }

  int lookup(AsciiString name) {
    return lookup(name, table, headerTable, seq);
  }

  int lookup(Http2Header header) {
    return lookup(header, table, headerTable, seq);
  }

  private static int lookup(final Http2Header header, final long[] table, final HpackDynamicTable headerTable,
                            final int seq) {
    return lookup0(table, headerTable, seq, header, hash(header), false);
  }

  private static int lookup(final AsciiString name, final long[] table, final HpackDynamicTable headerTable,
                            final int seq) {
    return lookup0(table, headerTable, seq, name, hash(name), true);
  }


  private static int lookup0(final long[] table, final HpackDynamicTable headerTable, final int seq, final Object value,
                             final int hash, final boolean name) {
    final int count = headerTable.length();
    final int mask = table.length - 1;

    int pos = ib(hash, mask);
    int dist = 0;

    while (true) {
      final long entry = table[pos];

      // Is this entry unused?
      if (entry == 0) {
        return -1;
      }

      final int entryHash = entryHash(entry);

      // Have we searched longer than the probe distance of this entry?
      final int entryIB = ib(entryHash, mask);
      final int entryProbeDistance = dib(entryIB, pos, table.length, mask);
      if (entryProbeDistance < dist) {
        return -1;
      }

      final int entrySeq = entrySeq(entry);
      final int entryTableIndex = entryTableIndex(seq, entrySeq);

      // Is this the header we're looking for and is it still valid?
      if (hash == entryHash && !entryExpired(entryTableIndex, count)) {
        final Http2Header entryHeader = headerTable.header(entryTableIndex);
        final Object entryValue;
        if (name) {
          entryValue = entryHeader.name();
        } else {
          entryValue = entryHeader;
        }
        if (entryValue.equals(value)) {
          return entryTableIndex;
        }
      }

      pos = (pos + 1) & mask;
      dist++;
    }
  }

  static boolean entryExpired(long entryTableIndex, int entryTableLength) {
    return entryTableIndex >= entryTableLength;
  }

  /**
   * Thomas Wang's 32 Bit Mix Function
   * http://web.archive.org/web/20071223173210/http://www.concentric.net/~Ttwang/tech/inthash.htm
   */
  static int mix(int key) {
    key += ~(key << 15);
    key ^= (key >> 10);
    key += (key << 3);
    key ^= (key >> 6);
    key += ~(key << 11);
    key ^= (key >> 16);
    return key;
  }

  static final int HEADER = 0x80000000;

  static boolean isHeader(int hash) {
    return (hash & HEADER) != 0;
  }

  static boolean isName(int hash) {
    return !isHeader(hash);
  }

  static int hash(Http2Header header) {
    final int hash = mix(header.hashCode()) | HEADER;
    return (hash == 0)
           ? 1_190_494_759
           : hash;
  }

  static int hash(AsciiString name) {
    final int hash = mix(name.hashCode()) & ~HEADER;
    return (hash == 0)
           ? 1_190_494_759
           : hash;
  }

  static long entry(final int seq, final int hash) {
    return ((long) seq) << 32 | (hash & 0xFFFFFFFFL);
  }

  static int entrySeq(final long entry) {
    return (int) (entry >>> 32);
  }

  static int entryHash(final long entry) {
    return (int) entry;
  }

  static int entryTableIndex(final int insertSeq, final int entrySeq) {
    return insertSeq - entrySeq;
  }

  /**
   * Initial Bucket
   */
  static int ib(final int hash, final int mask) {
    return hash & mask;
  }

  /**
   * Distance from Initial Bucket (DIB)
   */
  static int dib(final int ib, final int pos, final int capacity, final int mask) {
    return (pos + capacity - ib) & mask;
  }

  @Override
  public String toString() {
    final StringBuilder s = new StringBuilder();
    for (int i = 0; i < table.length; i++) {
      long entry = table[i];
      if (entry == 0) {
        continue;
      }
      final int entryTableIndex = entryTableIndex(seq, entrySeq(entry));
      if (entryTableIndex >= headerTable.length()) {
        continue;
      }
      final Http2Header header = headerTable.header(entryTableIndex);
      s.append('\'').append(header.name()).append(':').append(header.value()).append("' => ").append(entryTableIndex)
          .append(System.lineSeparator());
    }
    return s.toString();
  }

  void validate() {
    validate(table, headerTable, seq);
  }

  static void validate(long[] table, HpackDynamicTable headerTable, int seq) {
    final int mask = table.length - 1;
    Set<Http2Header> headers = new HashSet<>();
    Set<AsciiString> names = new HashSet<>();
    for (int i = 0; i < table.length; i++) {
      final long entry = table[i];
      if (entry == 0) {
        continue;
      }
      final int entryHash = entryHash(entry);
      final int entryIB = ib(entryHash, mask);
      final int entryProbeDistance = dib(entryIB, i, table.length, mask);
      final int entryTableIndex = entryTableIndex(seq, entrySeq(entry));
      final boolean entryExpired = entryExpired(entryTableIndex, headerTable.length());
      // Check that there's no duplicate entries
      if (!entryExpired) {
        Http2Header header = headerTable.header(entryTableIndex);
        if (isHeader(entryHash)) {
          if (headers.contains(header)) {
            throw new AssertionError();
          }
          headers.add(header);
        } else {
          if (names.contains(header.name())) {
            throw new AssertionError();
          }
          names.add(header.name());
        }
      }
      // Check that all entries between the entry desiredPos and actual pos has at least the same probe distance from their respective desired positions
      for (int j = entryIB; j < i; j = (j + 1) & mask) {
        final long e = table[i];
        final int h = entryHash(e);
        final int ib = ib(h, mask);
        final int pd = dib(ib, i, table.length, mask);
        if (pd < entryProbeDistance) {
          throw new AssertionError();
        }
      }
    }
    // Check that all table headers and header names can be looked up with the correct index
    final Map<Http2Header, Integer> headerIndices = new HashMap<>();
    final Map<AsciiString, Integer> nameIndices = new HashMap<>();
    for (int i = 0; i < headerTable.length(); i++) {
      final Http2Header header = headerTable.header(i);
      headerIndices.putIfAbsent(header, i);
      nameIndices.putIfAbsent(header.name(), i);
    }
    for (int i = 0; i < headerTable.length(); i++) {
      final Http2Header header = headerTable.header(i);
      final int ix = lookup(header, table, headerTable, seq);
      if (ix != headerIndices.get(header)) {
        lookup(header, table, headerTable, seq);
        throw new AssertionError();
      }
      if (lookup(header.name(), table, headerTable, seq) != nameIndices.get(header.name())) {
        throw new AssertionError();
      }
    }
  }

  List<String> inspect() {
    final int mask = table.length - 1;
    final List<String> entries = new ArrayList<>();
    for (int i = 0; i < table.length; i++) {
      long entry = table[i];
      if (entry == 0) {
        entries.add(i + ":");
      } else {
        final int entryTableIndex = entryTableIndex(seq, entrySeq(entry));
        final boolean isHeader = isHeader(entryHash(entry));
        final String type = isHeader ? "hædr" : "name";
        final String value;
        if (entryTableIndex < headerTable.length()) {
          final Http2Header header = headerTable.header(entryTableIndex);
          if (isHeader) {
            value = header.toString();
          } else {
            value = header.name().toString();
          }
        } else {
          value = "";
        }
        entries.add(String.format(
            "%d: ib=%03d pd=%03d hash=%08x seq=%08x tix=%03d type=%s value=%s",
            i, ib(entryHash(entry), mask), dib(entryHash(entry), i, table.length, mask), entryHash(entry),
            entrySeq(entry), entryTableIndex, type, value));
      }
    }
    return entries;
  }

  public void remove(final Http2Header header) {

  }
}