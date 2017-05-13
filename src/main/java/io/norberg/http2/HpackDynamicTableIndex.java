package io.norberg.http2;

import io.netty.util.AsciiString;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Robin Hood hashing index with backward shift deletion
 *
 * http://codecapsule.com/2013/11/17/robin-hood-hashing-backward-shift-deletion/
 */
final class HpackDynamicTableIndex {

  private final float loadFactor = 0.25f;

  private static final int MAX_CAPACITY = 1 << 30;
  private static final int INITIAL_CAPACITY = 32;
  private int growthThreshold;

  // header seq | header hash
  private long table[] = new long[INITIAL_CAPACITY];

  private int seq = 0;

  private final HpackDynamicTable headerTable;

  HpackDynamicTableIndex(final HpackDynamicTable headerTable) {
    this.headerTable = headerTable;
    this.growthThreshold = (int) (loadFactor * table.length / 2);
  }

  int lookup(Http2Header header) {
    return lookup(header.name(), header.value(), table, headerTable, seq);
  }

  int lookup(AsciiString name) {
    return lookup0(table, headerTable, seq, name, null, hash(name));
  }

  int lookup(AsciiString name, AsciiString value) {
    return lookup(name, value, table, headerTable, seq);
  }

  void insert(Http2Header header) {
    seq++;
    if (headerTable.length() > growthThreshold) {
      rehash(table.length << 1);
      return;
    }
    insert(table, header.name(), header.value(), hash(header.name(), header.value()), seq, seq, headerTable);
    insert(table, header.name(), null, hash(header.name()), seq, seq, headerTable);
  }

  void remove(Http2Header header) {
    int headerSeq = seq - headerTable.length();
    remove0(table, headerSeq, hash(header.name(), header.value()));
    remove0(table, headerSeq, hash(header.name()));
  }

  void clear() {
    Arrays.fill(table, 0);
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
    }
    final int insertSeq = this.seq;
    int seq = insertSeq - headerTable.length();
    for (int i = headerTable.length() - 1; i >= 0; i--) {
      final Http2Header header = headerTable.header(i);
      seq++;
      insert(table, header.name(), header.value(), hash(header.name(), header.value()), seq, insertSeq, headerTable);
      insert(table, header.name(), null, hash(header.name()), seq, insertSeq, headerTable);
    }
  }

  static void insert(final long[] table, final AsciiString insertName, final AsciiString insertValue,
      final int insertHash, final int insertSeq,
      final int tableSeq, final HpackDynamicTable headerTable) {
    final int capacity = table.length;
    final int mask = capacity - 1;
    final int insertIB = ib(insertHash, mask);

    AsciiString name = insertName;
    AsciiString value = insertValue;
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

      final int entryHash = entryHash(probeEntry);
      final int entrySeq = entrySeq(probeEntry);
      final int entryTableIndex = entryTableIndex(tableSeq, entrySeq);

      // Is the entry identical?
      if (hash == entryHash && name != null) {
        final Http2Header probeHeader = headerTable.header(entryTableIndex);
        if (probeHeader.name().equals(name) && (value == null || probeHeader.value().equals(value))) {
          table[pos] = entry(seq, hash);
          return;
        }
      }

      final int entryIB = ib(entryHash, mask);
      final int entryDIB = dib(entryIB, pos, capacity, mask);

      // Displace the current entry?
      if (dist > entryDIB) {
        table[pos] = entry(seq, hash);
        seq = entrySeq;
        hash = entryHash;
        dist = entryDIB;
        name = null;
      }

      dist++;
      pos = next(pos, mask);
    }
  }

  private void remove0(final long[] table, final int seq, final int hash) {
    final int capacity = table.length;
    final int mask = capacity - 1;

    int pos = ib(hash, mask);
    int dist = 0;

    final long needle = entry(seq, hash);

    // Find our bucket
    while (true) {
      final long entry = table[pos];
      if (entry == needle) {
        break;
      }

      // Have we searched longer than the probe distance of this entry?
      final int entryHash = entryHash(entry);
      final int entryIB = ib(entryHash, mask);
      final int entryProbeDistance = dib(entryIB, pos, table.length, mask);
      if (entryProbeDistance < dist) {
        return;
      }

      pos = (pos + 1) & mask;
      dist++;
    }

    // Shift buckets left
    int prevPos = pos;
    int probePos = next(prevPos, mask);
    while (true) {
      final long probeEntry = table[probePos];
      if (probeEntry == 0) {
        table[prevPos] = 0;
        return;
      }
      final int probeHash = entryHash(probeEntry);
      final int probeIB = ib(probeHash, mask);
      final int probeDIB = dib(probeIB, probePos, capacity, mask);
      if (probeDIB == 0) {
        table[prevPos] = 0;
        return;
      }
      table[prevPos] = table[probePos];
      prevPos = probePos;
      probePos = next(probePos, mask);
    }
  }

  static int next(final int i, final int mask) {
    return (i + 1) & mask;
  }

  private static int lookup(AsciiString name, AsciiString value, final long[] table,
      final HpackDynamicTable headerTable,
      final int seq) {
    return lookup0(table, headerTable, seq, name, value, hash(name, value));
  }

  private static int lookup(final AsciiString name, final long[] table, final HpackDynamicTable headerTable,
      final int seq) {
    return lookup0(table, headerTable, seq, name, null, hash(name));
  }


  private static int lookup0(final long[] table, final HpackDynamicTable headerTable, final int seq,
      final AsciiString name, final AsciiString value,
      final int hash) {
    final int mask = table.length - 1;

    int pos = ib(hash, mask);
    int dist = 0;

    while (true) {
      final long entry = table[pos];

      // Is this entry unused?
      if (entry == 0) {
        return 0;
      }

      final int entryHash = entryHash(entry);

      // Have we searched longer than the probe distance of this entry?
      final int entryIB = ib(entryHash, mask);
      final int entryProbeDistance = dib(entryIB, pos, table.length, mask);
      if (entryProbeDistance < dist) {
        return 0;
      }

      final int entrySeq = entrySeq(entry);
      final int entryTableIndex = entryTableIndex(seq, entrySeq);

      if (hash == entryHash) {
        final Http2Header entryHeader = headerTable.header(entryTableIndex);
        if (entryHeader.name().equals(name) &&
            (value == null || entryHeader.value().equals(value))) {
          return entryTableIndex + 1;
        }
      }

      pos = (pos + 1) & mask;
      dist++;
    }
  }

  /**
   * Thomas Wang's 32 Bit Mix Function
   * http://web.archive.org/web/20071223173210/http://www.concentric.net/~Ttwang/tech/inthash.htm
   */
  private static int mix(int key) {
    key += ~(key << 15);
    key ^= (key >> 10);
    key += (key << 3);
    key ^= (key >> 6);
    key += ~(key << 11);
    key ^= (key >> 16);
    return key;
  }

  private static final int HEADER = 0x80000000;

  private static boolean isHeader(int hash) {
    return (hash & HEADER) != 0;
  }

  private static boolean isName(int hash) {
    return !isHeader(hash);
  }

  private static int hash(AsciiString name, AsciiString value) {
    final int hash = mix((31 * name.hashCode()) ^ value.hashCode()) | HEADER;
    return (hash == 0)
        ? 1_190_494_759
        : hash;
  }

  private static int hash(AsciiString name) {
    final int hash = mix(name.hashCode()) & ~HEADER;
    return (hash == 0)
        ? 1_190_494_759
        : hash;
  }

  private static long entry(final int seq, final int hash) {
    return ((long) seq) << 32 | (hash & 0xFFFFFFFFL);
  }

  private static int entrySeq(final long entry) {
    return (int) (entry >>> 32);
  }

  private static int entryHash(final long entry) {
    return (int) entry;
  }

  private static int entryTableIndex(final int insertSeq, final int entrySeq) {
    return insertSeq - entrySeq;
  }

  /**
   * Initial Bucket
   */
  private static int ib(final int hash, final int mask) {
    return hash & mask;
  }

  /**
   * Distance from Initial Bucket (DIB)
   */
  private static int dib(final int ib, final int pos, final int capacity, final int mask) {
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

  private static void validate(long[] table, HpackDynamicTable headerTable, int seq) {
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
      // Check that there's no duplicate entries
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
      headerIndices.putIfAbsent(header, i + 1);
      nameIndices.putIfAbsent(header.name(), i + 1);
    }
    for (int i = 0; i < headerTable.length(); i++) {
      final Http2Header header = headerTable.header(i);
      final int ix = lookup(header.name(), header.value(), table, headerTable, seq);
      if (ix != headerIndices.get(header)) {
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
}