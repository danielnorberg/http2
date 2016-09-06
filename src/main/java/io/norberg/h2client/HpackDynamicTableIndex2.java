package io.norberg.h2client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class HpackDynamicTableIndex2 {

  private int capacity = 16;
  private int mask = capacity - 1;
  private static final int REHASH_MASK = 0x00FFFFFF;

  // header seq | header hash
  private long table[] = new long[capacity];

  private int seq = 0;

  private final HpackDynamicTable headerTable;

  HpackDynamicTableIndex2(final HpackDynamicTable headerTable) {
    this.headerTable = headerTable;
  }

  void insert(Http2Header header) {
    seq++;
    insert(table, header, seq, headerTable);
//    validateInvariants();
  }

  void validateInvariants() {
    Set<Http2Header> headers = new HashSet<>();
    for (int i = 0; i < table.length; i++) {
      final long entry = table[i];
      if (entry == 0) {
        continue;
      }
      final int entryHash = entryHash(entry);
      final int entryDesiredPos = ib(entryHash, mask);
      final int entryProbeDistance = dib(entryHash, i, table.length, mask);
      final int entryTableIndex = entryTableIndex(seq, entrySeq(entry));
      final boolean entryExpired = entryExpired(entryTableIndex, headerTable.length());
      if (!entryExpired) {
        Http2Header header = headerTable.header(entryTableIndex);
        if (headers.contains(header)) {
          throw new AssertionError();
        }
        headers.add(header);
      }
      // Check that all entries between the entry desiredPos and actual pos has at least the same probe distance from their respective desired positions
      for (int j = entryDesiredPos; j < i; j = (j + 1) & mask) {
        final long e = table[i];
        final int h = entryHash(e);
        final int dp = ib(h, mask);
        final int pd = dib(h, i, table.length, mask);
        if (pd < entryProbeDistance) {
          throw new AssertionError();
        }
      }
    }
    for (int i = 0; i < headerTable.length(); i++) {
      final Http2Header header = headerTable.header(i);
      if (lookup(header) != i) {
        throw new AssertionError();
      }
    }
  }

  static long get(final long[] array, final int i) {
    return array[i & (array.length - 1)];
  }

  static void insert(final long[] table, final Http2Header insertHeader, final int insertSeq, final HpackDynamicTable headerTable) {
    final int count = headerTable.length();
    final int capacity = table.length;
    final int mask = capacity - 1;
    boolean insert = true;
    int seq = insertSeq;
    Http2Header header = insertHeader;
    int hash = hash(header);
    int desiredPos = ib(hash, mask);
    int dist = 0;
    int insertPos = desiredPos;
    int probePos = desiredPos;

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
      final int probeDIB = dib0(probeIB, probePos, capacity, mask);

      // Is the probed entry header identical our entry header?
      if (header != null) {
        Http2Header probeHeader = headerTable.header(probeTableIndex);
        if (header.equals(probeHeader)) {
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
          header = null;
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
          header = null;
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
      if (inRange(probeIB, next(insertPos, mask), probePos)) {
        // Yes, so put our entry in the insertion bucket (if we have one) and the probed entry in its IB.
        // Then keep looking for the stop-bucket.
        clear(table, insertPos, probeIB);
        if (insert) {
          table[insertPos] = entry(seq, hash);
          header = null;
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
        final int probeInsertDIB = dib0(probeIB, insertPos, capacity, mask);
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
          header = null;
          continue;
        } else {
          // No, so put the probed entry in the insertion bucket and keep looking for the stop bucket
          table[insertPos] = probeEntry;
          insertPos = next(insertPos, mask);
          probePos = next(probePos, mask);
          dist++;
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

  static int prev(final int i, final int mask) {
    return (i - 1) & mask;
  }

  int[] probeDistances() {
    int[] distances = new int[table.length];
    for (int i = 0; i < table.length; i++) {
      long entry = table[i];
      if (entry == 0) {
        continue;
      }
      distances[i] = dib(entryHash(entry), i, table.length, mask);
    }
    return distances;
  }

  static boolean entryExpired(long entryTableIndex, int entryTableLength) {
    return entryTableIndex >= entryTableLength;
  }

  int search(int hash, Http2Header header) {
    for (int i = 0; i < table.length; i++) {
      final long entry = table[i];
      if (entryHash(entry) == hash) {
        return i;
      }
    }
    return -1;
  }

  int lookup(Http2Header header) {
    final int count = headerTable.length();
    final int hash = hash(header);

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
      final int entryProbeDistance = dib(entryHash, pos, table.length, mask);
      if (entryProbeDistance < dist) {
        return -1;
      }

      final int entrySeq = entrySeq(entry);
      final int entryTableIndex = entryTableIndex(seq, entrySeq);

      // Is this the header we're looking for and is it still valid?
      if (hash == entryHash && !entryExpired(entryTableIndex, count)) {
        final Http2Header entryHeader = headerTable.header(entryTableIndex);
        if (entryHeader.equals(header)) {
          return entryTableIndex;
        }
      }

      pos = (pos + 1) & mask;
      dist++;
    }
  }

  static int entryHash(final long entry) {
    return (int) entry;
  }

  private int rehash() {
    throw new UnsupportedOperationException("TODO");
  }

  static int mix2(int key) {
    key += ~(key << 15);
    key ^= (key >> 10);
    key += (key << 3);
    key ^= (key >> 6);
    key += ~(key << 11);
    key ^= (key >> 16);
    return key;
  }

  static int mix(int key) {
    int h = key;
    return h ^ (h >>> 16);
//    int h = key * -1640531527;
//    return h ^ h >> 16;
  }

  static int hash(Http2Header header) {
    final int hash = mix2(header.hashCode());
//    final int hash = mix(header.hashCode());
//    final int hash = mix2(header.name().hashCode()) ^ mix2(header.value().hashCode());
    return (hash == 0)
           ? 1_190_494_759
           : hash;
  }

  static int entryTableIndex(final int seq, final int entrySeq) {
    return seq - entrySeq;
  }

  static int entrySeq(final long entry) {
    return (int) (entry >>> 32);
  }

  /**
   * Distance from Initial Bucket (DIB)
   */
  static int dib(final int hash, final int pos, final int capacity, final int mask) {
    final int ib = ib(hash, mask);
    return dib0(ib, pos, capacity, mask);
  }

  static int dib0(final int ib, final int pos, final int capacity, final int mask) {
    return (pos + capacity - ib) & mask;
  }

  static long entry(final int seq, final int hash) {
    return ((long) seq) << 32 | (hash & 0xFFFFFFFFL);
  }

  /**
   * Initial Bucket
   */
  static int ib(final int hash, final int mask) {
    return hash & mask;
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
      s.append('\'').append(header.name()).append(':').append(header.value()).append("' => ").append(entryTableIndex).append(System.lineSeparator());
    }
    return s.toString();
  }

  public List<String> inspect() {
    final List<String> entries = new ArrayList<>();
    for (int i = 0; i < table.length; i++) {
      long entry = table[i];
      final String hdr;
      final int entryTableIndex = entryTableIndex(seq, entrySeq(entry));
      if (entry == 0) {
        entries.add(i + ":");
      } else {
        if (entryTableIndex < headerTable.length()) {
          hdr = headerTable.header(entryTableIndex).toString();
        } else {
          hdr = "";
        }
        entries.add(String.format(
            "%d: ib=%03d pd=%03d hash=%08x seq=%08x tix=%03d hdr=%s",
            i, ib(entryHash(entry), mask), dib(entryHash(entry), i, table.length, mask), entryHash(entry), entrySeq(entry),
            entryTableIndex, hdr));
      }
    }
    return entries;
  }
}