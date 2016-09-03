package io.norberg.h2client;

import com.koloboke.collect.hash.HashConfig;
import com.koloboke.compile.KolobokeMap;

@KolobokeMap
abstract class KolobokeIndex {
  static KolobokeIndex withExpectedSize(int expectedSize) {
    return new KolobokeKolobokeIndex(expectedSize);
  }
  static KolobokeIndex withExpectedSize(HashConfig hashConfig, int expectedSize) {
    return new KolobokeKolobokeIndex(hashConfig, expectedSize);
  }
  abstract int put(Object key, int value);

  abstract int getInt(Object key);

  abstract boolean remove(Object key, int value);
}
