package io.norberg.h2client;

import java.util.ArrayList;
import java.util.List;

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
    insert0(header, seq);
    validateInvariants();
  }

  void validateInvariants() {
    for (int i = 0; i < table.length; i++) {
      final long entry = table[i];
      if (entry == 0) {
        continue;
      }
      final int entryHash = entryHash(entry);
      final int entryDesiredPos = desiredPos(entryHash);
      final int entryProbeDistance = probeDistance(entryHash, i);
      // Check that all entries between the entry desiredPos and actual pos has at least the same probe distance from their respective desired positions
      for (int j = entryDesiredPos; j < i; j = (j + 1) & mask) {
        final long e = table[i];
        final int h = entryHash(e);
        final int dp = desiredPos(h);
        final int pd = probeDistance(h, i);
        if (pd < entryProbeDistance) {
          throw new AssertionError();
        }
      }
    }
  }

  private void insert0(Http2Header header, int seq) {
    if ((seq & REHASH_MASK) == 0) {
      rehash();
    }

    final int count = headerTable.length();
    int hash = hash(header);
    int pos = desiredPos(hash);
    int dist = 0;

    // Probe for an empty slot
    while (true) {

      if (dist == capacity) {
        rehash();
      }

      final long entry = table[pos];

      // Is this entry unused?
      if (entry == 0) {
        table[pos] = entry(seq, hash);
        break;
      }

      int entrySeq = entrySeq(entry);
      int entryTableIndex = entryTableIndex(seq, entrySeq);
      int entryHash = entryHash(entry);

      // Is the entry equal to the header we're trying to insert?
      if (hash == entryHash) {
        if (expired(entryTableIndex, count)) {
          table[pos] = entry(seq, hash);
          break;
        }
        final Http2Header entryHeader = headerTable.header(entryTableIndex);
        if (entryHeader.equals(header)) {
          // Replace the entry with the newer header
          table[pos] = entry(seq, hash);
          break;
        }
      } else {
        // Does this entry have a shorter probe distance than the header we are trying to insert?
        int entryProbeDistance = probeDistance(entryHash, pos);
        if (entryProbeDistance < dist) {
          // Insert the header here
          table[pos] = entry(seq, hash);
          // Is the entry expired?
          if (expired(entryTableIndex, count)) {
            break;
          }
          // Keep probing for a slot for the displaced entry
          seq = entrySeq;
          hash = entryHash;
          dist = entryProbeDistance;
        }
      }

      pos = (pos + 1) & mask;
      dist++;
    }
  }

  int[] probeDistances() {
    int[] distances = new int[table.length];
    for (int i = 0; i < table.length; i++) {
      long entry = table[i];
      if (entry == 0) {
        continue;
      }
      distances[i] = probeDistance(entryHash(entry), i);
    }
    return distances;
  }

  private boolean expired(long entryTableIndex, int count) {
    return entryTableIndex >= count;
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

    int pos = desiredPos(hash);
    int dist = 0;

    int debugPos = search(hash, header);

    while (true) {
      final long entry = table[pos];

      // Is this entry unused?
      if (entry == 0) {
        return -1;
      }

      final int entryHash = entryHash(entry);

      // Have we searched longer than the probe distance of this entry?
      final int entryProbeDistance = probeDistance(entryHash, pos);
      if (entryProbeDistance < dist) {
        return -1;
      }

      final int entrySeq = entrySeq(entry);
      final int entryTableIndex = entryTableIndex(seq, entrySeq);

      // Is this the header we're looking for and is it still valid?
      if (hash == entryHash && !expired(entryTableIndex, count)) {
        final Http2Header entryHeader = headerTable.header(entryTableIndex);
        if (entryHeader.equals(header)) {
          return entryTableIndex;
        }
      }

      pos = (pos + 1) & mask;
      dist++;
    }
  }

  private int entryHash(final long entry) {
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

  private int entryTableIndex(final int seq, final int entrySeq) {
    return seq - entrySeq;
  }

  private int entrySeq(final long entry) {
    return (int) (entry >>> 32);
  }

  private int probeDistance(final int hash, final int pos) {
    return (pos + capacity - desiredPos(hash)) & mask;
  }

  private long entry(final int seq, final int hash) {
    return ((long) seq) << 32 | (hash & 0xFFFFFFFFL);
  }

  private int desiredPos(final int hash) {
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
      final int entryTableIndex = entryTableIndex(seq, entrySeq(entry));
      final String hdr;
      if (entryTableIndex < headerTable.length()) {
        hdr = headerTable.header(entryTableIndex).toString();
      } else {
        hdr = "";
      }
      entries.add(String.format(
          "%d: pd=%03d hash=%08x seq=%08x tix=%03d hdr=%s",
          i, probeDistance(entryHash(entry), i), entryHash(entry), entrySeq(entry),
          entryTableIndex, hdr));
    }
    return entries;
  }
}