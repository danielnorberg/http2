package io.norberg.h2client;

import java.util.Arrays;

import io.netty.util.AsciiString;

class HpackDynamicTableIndex {

  private int n;
  private Object[] keys;
  private int[] values;
  private int mask;
  private int size;
  private int maxFill;

  private int offset;
  private final float f = 0.05f;

  HpackDynamicTableIndex(final int expected) {
    this.n = arraySize(expected, f);
    this.mask = n - 1;
    this.maxFill = maxFill(n, f);
    this.keys = new Object[n + 1];
    this.values = new int[n + 1];
  }

  int index(final AsciiString name, final AsciiString value) {
    final Object[] keys = this.keys;
    final int[] values = this.values;
    final int hashCode = Http2Header.hashCode(name, value);
    Object curr;
    int pos;
    if ((curr = keys[pos = hash(hashCode) & mask]) == null) {
      return 0;
    } else if (curr instanceof Http2Header && ((Http2Header) curr).equals(name, value)) {
      return values[pos];
    } else {
      while ((curr = keys[pos = pos + 1 & mask]) != null) {
        if (curr instanceof Http2Header && ((Http2Header) curr).equals(name, value)) {
          return index0(values[pos]);
        }
      }
      return 0;
    }
  }

  int index(final AsciiString name) {
    return index0(name);
  }

  void add(final Http2Header header) {
    offset++;
    insert(header, offset);
    insert(header.name(), offset);
  }

  void remove(final Http2Header header, int index) {
    remove0(header, index);
    remove0(header.name(), index);
  }

  void clear() {
    this.size = 0;
    Arrays.fill(keys, null);
  }

  private int index0(Object k) {
    final Object[] keys = this.keys;
    final int[] values = this.values;
    Object curr;
    int pos;
    if ((curr = keys[pos = hash(k.hashCode()) & mask]) == null) {
      return 0;
    } else if (k.equals(curr)) {
      return index0(values[pos]);
    } else {
      while ((curr = keys[pos = pos + 1 & mask]) != null) {
        if (k.equals(curr)) {
          return index0(values[pos]);
        }
      }
      return 0;
    }
  }

  private int index0(final int value) {
    return offset - value + 1;
  }

  private void remove0(final Object k, final int value) {
    final Object[] keys = this.keys;
    final int[] values = this.values;
    Object curr;
    int pos;
    int ix = index0(value);
    if ((curr = keys[pos = hash(k.hashCode()) & mask]) == null) {
    } else if (k.equals(curr)) {
      if (values[pos] == ix) {
        remove0(pos);
      }
    } else {
      while ((curr = keys[pos = pos + 1 & mask]) != null) {
        if (k.equals(curr)) {
          if (values[pos] == ix) {
            remove0(pos);
          }
          return;
        }
      }
    }
  }

  private void remove0(final int pos) {
    --this.size;
    this.shiftKeys(pos);
  }

  private void insert(Object k, int v) {
    final Object[] keys = this.keys;
    final int[] values = this.values;
    int pos;
    Object curr;

    if ((curr = keys[pos = hash(k.hashCode()) & mask]) != null) {
      if (curr.equals(k)) {
        values[pos] = v;
        return;
      }

      while ((curr = keys[pos = pos + 1 & mask]) != null) {
        if (curr.equals(k)) {
          values[pos] = v;
          return;
        }
      }
    }

    keys[pos] = k;
    values[pos] = v;

    if (size++ >= maxFill) {
      rehash(arraySize(size + 1, f));
    }
  }

  private void rehash(int newN) {
    final Object[] keys = this.keys;
    final int[] values = this.values;
    int mask = newN - 1;
    Object[] newKey = new Object[newN + 1];
    int[] newValue = new int[newN + 1];
    int i = this.n;

    int pos;
    for (int j = this.size; j-- != 0; newValue[pos] = values[i]) {
      do {
        --i;
      } while (keys[i] == null);

      if (newKey[pos = hash(keys[i].hashCode()) & mask] != null) {
        while (newKey[pos = pos + 1 & mask] != null) {
        }
      }

      newKey[pos] = keys[i];
    }

    newValue[newN] = values[this.n];
    this.n = newN;
    this.mask = mask;
    this.maxFill = maxFill(this.n, this.f);
    this.keys = newKey;
    this.values = newValue;
  }

  private void shiftKeys(int pos) {
    final Object[] keys = this.keys;
    final int[] values = this.values;
    while (true) {
      int last = pos;
      pos = pos + 1 & mask;

      Object curr;
      while (true) {
        if ((curr = keys[pos]) == null) {
          keys[last] = null;
          return;
        }

        int slot = hash(curr.hashCode()) & mask;
        if (last <= pos) {
          if (last >= slot || slot > pos) {
            break;
          }
        } else if (last >= slot && slot > pos) {
          break;
        }

        pos = pos + 1 & mask;
      }

      keys[last] = curr;
      values[last] = values[pos];
    }
  }

  private static int hash(int x) {
    int h = x * -1640531527;
    return h ^ h >>> 16;
  }

  private static int maxFill(int n, float f) {
    return Math.min((int) Math.ceil((double) ((float) n * f)), n - 1);
  }

  private static int arraySize(int expected, float f) {
    long s = Math.max(2L, nextPowerOfTwo((long) Math.ceil((double) ((float) expected / f))));
    if (s > 1073741824L) {
      throw new IllegalArgumentException("Too large (" + expected + " expected elements with load factor " + f + ")");
    } else {
      return (int) s;
    }
  }

  private static long nextPowerOfTwo(long x) {
    if (x == 0L) {
      return 1L;
    } else {
      --x;
      x |= x >> 1;
      x |= x >> 2;
      x |= x >> 4;
      x |= x >> 8;
      x |= x >> 16;
      return (x | x >> 32) + 1L;
    }
  }

  @Override
  public String toString() {
    if (size == 0) {
      return "{}";
    }

    StringBuilder sb = new StringBuilder();
    sb.append('{');
    for (int i = 0; i < keys.length; i++) {
      final Object key = keys[i];
      if (key == null) {
        continue;
      }
      final int value = values[i];
      sb.append('"');
      sb.append(key);
      sb.append('"');
      sb.append('=');
      sb.append(index0(value));
      if (i + 1 < keys.length) {
        sb.append(',').append(' ');
      }
    }
    return sb.append('}').toString();
  }
}
