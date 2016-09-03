package io.norberg.h2client;

import com.koloboke.compile.KolobokeMap;

@KolobokeMap
abstract class IntIntIndex {
  static IntIntIndex withExpectedSize(int expectedSize) {
    return new KolobokeIntIntIndex(expectedSize);
  }
  abstract int put(int key, int value);

  abstract int get(int key);

  abstract int remove(int key);
}
