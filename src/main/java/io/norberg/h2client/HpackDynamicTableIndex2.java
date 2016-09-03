package io.norberg.h2client;

final class HpackDynamicTableIndex2 {

  private int capacity = 16;
  private int mask = capacity - 1;

  // header seq | header hash
  private long table[] = new long[capacity];

  private int seq = 0;

  private final HpackDynamicTable headerTable;

  HpackDynamicTableIndex2(final HpackDynamicTable headerTable) {
    this.headerTable = headerTable;
    for (int i = 0; i < table.length; i++) {
      table[i] = Long.MIN_VALUE;
    }
  }

  void insert(Http2Header header) {
    seq++;

    final int count = headerTable.length();
    int hash = header.hashCode();
    int pos = desiredPos(hash);
    int dist = 0;

    // Probe for an empty slot
    while (true) {
      long entry = table[pos];
      long entrySeq = entrySeq(entry);
      long entryTableIndex = entryTableIndex(seq, entrySeq);

      // Has this entry expired?
      if (entryTableIndex > count) {
        table[pos] = entry(seq, hash);
        break;
      }

      // Is the entry equal to the header we're trying to insert?
      int entryHash = (int) entry;
      if (hash == entryHash) {
        final Http2Header entryHeader = headerTable.header((int) entryTableIndex);
        if (!entryHeader.equals(header)) {
          // Replace the entry with the newer header
          table[pos] = entry(seq, hash);
          break;
        }
        continue;
      }

      // Does this entry have a shorter probe distance than the value we are trying to insert?
      if (probeDistance(entryHash, pos) < dist) {
        // Swap the entry with the value and find another place for that entry
        table[pos] = entry(seq, hash);
        hash = entryHash;
        dist = 0;
        continue;
      }

      dist++;
    }
  }

  int lookup(Http2Header header) {
    final int count = headerTable.length();
    int hash = header.hashCode();
    int pos = desiredPos(hash);
    int dist = 0;

    while (true) {
      long entry = table[pos];
      int entryHash = (int) entry;
      long entrySeq = entrySeq(entry);
      long entryTableIndex = entryTableIndex(seq, entrySeq);

      // Has this entry expired?
      if (entryTableIndex > count) {
        return 0;
      }

      // Is this the header we're looking for?
      if (hash == entryHash) {
        final Http2Header entryHeader = headerTable.header((int)entryTableIndex);
        if (entryHeader.equals(header)) {
          return tableIndex;
        }
      }

      // Have we searched longer than the probe distance of this entry?
      if (probeDistance(entryHash, pos) < dist) {
        return 0;
      }

      dist++;
    }
  }


  private void insert(final Http2Header header, final int seq) {
    final int count = headerTable.length();
    int hash = header.hashCode();
    int pos = desiredPos(hash);
    int dist = 0;

    // Probe for an empty slot
    while (true) {
      long entry = table[pos];
      int entrySeq = entrySeq(entry);
      int entryAge = entryTableIndex(seq, entrySeq);

      // Has this entry expired?
      if (entryAge > count) {
        table[pos] = entry(seq, hash);
        break;
      }

      // Is the entry equal to the header we're trying to insert?
      int entryHash = (int) entry;
      if (hash == entryHash) {
        int tableIndex = seq - entrySeq;
        final Http2Header entryHeader = headerTable.header(tableIndex);
        if (!entryHeader.equals(header)) {
          // Replace the entry with the newer header
          table[pos] = entry(seq, hash);
          break;
        }
        continue;
      }

      // Does this entry have a shorter probe distance than the value we are trying to insert?
      if (probeDistance(entryHash, pos) < dist) {
        // Swap the entry with the value and find another place for that entry
        table[pos] = entry(seq, hash);
        hash = entryHash;
      }
    }
  }

  private long entryTableIndex(final long seq, final long entrySeq) {
    return seq - entrySeq;
  }

  private long entrySeq(final long entry) {
    return entry >>> 32;
  }

  private int probeDistance(final int hash, final int pos) {
    return (pos + capacity - desiredPos(hash)) & mask;
  }

  private long entry(final int seq, final int hash) {
    return ((long) seq) << 32 | (((long) hash) & 0x0000FFFFL);
  }

  private int desiredPos(final int hash) {
    return hash & mask;
  }
}