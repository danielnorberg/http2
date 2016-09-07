package io.norberg.h2client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.netty.util.AsciiString;

final class HpackDynamicTableIndex2 {

  // TODO: load factor - dynamic capacity
  private int capacity = 256;
  private int mask = capacity - 1;

  // header seq | header hash
  private long table[] = new long[capacity];

  private int seq = 0;

  private final HpackDynamicTable headerTable;

  HpackDynamicTableIndex2(final HpackDynamicTable headerTable) {
    this.headerTable = headerTable;
  }

  void insert(Http2Header header) {
    seq++;
    insert(table, header, hash(header), seq, headerTable, false);
    insert(table, header.name(), hash(header.name()), seq, headerTable, true);
  }

  static long get(final long[] array, final int i) {
    return array[i & (array.length - 1)];
  }

  static void insert(final long[] table, final Object insertValue, final int insertHash, final int insertSeq,
                     final HpackDynamicTable headerTable,
                     boolean name) {
    final int count = headerTable.length();
    final int capacity = table.length;
    final int mask = capacity - 1;
    final int insertIB = ib(insertHash, mask);

    boolean insert = true;
    Object value = insertValue;
    int seq = insertSeq;
    int hash = insertHash;
    int dist = 0;
    int insertPos = insertIB;
    int probePos = insertIB;

    while (true) {
      if (dist == capacity) {
        throw new AssertionError();
      }

      final long probeEntry = table[probePos];

      // Is the probed bucket unused?
      if (probeEntry == 0) {
        // We're done. Store our entry if we have one and bail.
        if (insert) {
          table[insertPos] = entry(seq, hash);
          if (insertPos != probePos) {
            clear(table, next(insertPos, mask), probePos);
          }
        } else {
          clear(table, insertPos, probePos);
        }
        return;
      }

      int probeSeq = entrySeq(probeEntry);
      int probeTableIndex = entryTableIndex(insertSeq, probeSeq);
      int probeHash = entryHash(probeEntry);
      final boolean probeExpired = entryExpired(probeTableIndex, count);

      // Is the probed entry expired?
      if (probeExpired) {
        // Keep probing for the stop-bucket
        probePos = next(probePos, mask);
        continue;
      }

      final int probeIB = ib(probeHash, mask);
      final int probeDIB = dib(probeIB, probePos, capacity, mask);

      // Is the probed entry header identical our entry header?
      if (hash == probeHash && value != null) {
        final Object probeValue;
        final Http2Header probeHeader = headerTable.header(probeTableIndex);
        if (name) {
          probeValue = probeHeader.name();
        } else {
          probeValue = probeHeader;
        }
        if (value.equals(probeValue)) {
          // Keep probing for the stop-bucket
          probePos = next(probePos, mask);
          continue;
        }
      }

      // Is the probed entry already optimally located?
      if (probeDIB == 0) {
        // Do we have an entry to store?
        if (!insert) {
          // No, then we're done.
          clear(table, insertPos, probePos);
          return;
        }
        // Yes, so put our entry in the insertion bucket
        table[insertPos] = entry(seq, hash);
        // Did this displace the probed entry?
        if (insertPos != probePos) {
          // No, then we're done.
          clear(table, next(insertPos, mask), probePos);
          return;
        } else {
          // Look for a new bucket for the displaced entry
          seq = probeSeq;
          hash = probeHash;
          value = null;
          dist = 1;
          probePos = next(probePos, mask);
          insertPos = probePos;
          continue;
        }
      }

      // Do we have any empty buckets to fill?
      if (insertPos == probePos) {
        // No. Ok, do we have an entry to insert?
        if (!insert) {
          // No, then we're done.
          return;
        }
        // Is the current entry better located than our entry would be?
        if (dist > probeDIB) {
          // Yes, then put our entry in the insertion bucket and look for a new bucket for the displaced entry
          table[insertPos] = entry(seq, hash);
          value = null;
          seq = probeSeq;
          hash = probeHash;
          dist = probeDIB + 1;
          probePos = next(probePos, mask);
          insertPos = probePos;
          continue;
        } else {
          // No, so let the probed entry stay and keep looking for a bucket for our entry
          insertPos = next(insertPos, mask);
          probePos = insertPos;
          dist++;
          continue;
        }
      }

      // Is the IB of the probed entry after the insertion bucket?
      final int probeInsertDIB = dib(probeIB, insertPos, capacity, mask);
      if (probeInsertDIB > probeDIB) {
        // Yes, so put our entry in the insertion bucket (if we have one) and the probed entry in its IB.
        // Then keep looking for the stop-bucket.
        clear(table, insertPos, probeIB);
        if (insert) {
          table[insertPos] = entry(seq, hash);
          value = null;
          insert = false;
        }
        table[probeIB] = probeEntry;
        insertPos = next(probeIB, mask);
        probePos = next(probePos, mask);
        continue;
      } else {
        // Do we have an entry to insert?
        if (!insert) {
          // No, so move the probed entry and then keep looking for the stop bucket
          table[insertPos] = probeEntry;
          insertPos = next(insertPos, mask);
          probePos = next(probePos, mask);
          continue;
        }
        // Would the probed entry be better located in the insertion bucket than our entry?
        if (dist > probeInsertDIB) {
          // Yes, so put our entry there and the probed entry in the next bucket and keep looking for the stop bucket
          table[insertPos] = entry(seq, hash);
          insertPos = next(insertPos, mask);
          table[insertPos] = probeEntry;
          if (insertPos == probePos) {
            // We're done here
            return;
          }
          insertPos = next(insertPos, mask);
          probePos = next(probePos, mask);
          insert = false;
          value = null;
          continue;
        } else {
          // No, so put the probed entry in the insertion bucket and keep looking for the stop bucket
          table[insertPos] = probeEntry;
          insertPos = next(insertPos, mask);
          probePos = next(probePos, mask);
          dist++;
          continue;
        }
      }
    }
  }

  private static boolean inRange(final int ix, final int start, final int end) {
    if (end < start) {
      return ix < end || ix >= start;
    } else {
      return ix >= start && ix < end;
    }
  }

  private static void clear(final long[] table, final int start, final int end) {
    if (end < start) {
      Arrays.fill(table, start, table.length, 0);
      Arrays.fill(table, 0, end, 0);
    } else {
      Arrays.fill(table, start, end, 0);
    }
  }

  static int next(final int i, final int mask) {
    return (i + 1) & mask;
  }

  static boolean entryExpired(long entryTableIndex, int entryTableLength) {
    return entryTableIndex >= entryTableLength;
  }

  int lookup(AsciiString name) {
    return lookup0(name, hash(name), true);
  }

  int lookup(Http2Header header) {
    return lookup0(header, hash(header), false);
  }

  private int lookup0(final Object value, final int hash, final boolean name) {
    final int count = headerTable.length();

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
      if (lookup(header) != headerIndices.get(header)) {
        throw new AssertionError();
      }
      if (lookup(header.name()) != nameIndices.get(header.name())) {
        throw new AssertionError();
      }
    }
  }

  List<String> inspect() {
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