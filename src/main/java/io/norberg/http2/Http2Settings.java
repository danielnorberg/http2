package io.norberg.http2;

import io.netty.util.collection.CharObjectHashMap;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

public class Http2Settings extends CharObjectHashMap<Long> {

  final static char HEADER_TABLE_SIZE = 0x1;
  final static char ENABLE_PUSH = 0x2;
  final static char MAX_CONCURRENT_STREAMS = 0x3;
  final static char INITIAL_WINDOW_SIZE = 0x4;
  final static char MAX_FRAME_SIZE = 0x5;
  final static char MAX_HEADER_LIST_SIZE = 0x6;

  private static final Long FALSE = 0L;
  private static final Long TRUE = 1L;

  public OptionalLong headerTableSize() {
    return getOptionalLong(HEADER_TABLE_SIZE);
  }

  public Optional<Boolean> enablePush() {
    return getOptionalBoolean(ENABLE_PUSH);
  }

  public OptionalLong maxConcurrentStreams() {
    return getOptionalLong(MAX_CONCURRENT_STREAMS);
  }

  public OptionalInt initialWindowSize() {
    return getOptionalInt(INITIAL_WINDOW_SIZE);
  }

  public OptionalInt maxFrameSize() {
    return getOptionalInt(MAX_FRAME_SIZE);
  }

  public OptionalLong maxHeaderListSize() {
    return getOptionalLong(MAX_HEADER_LIST_SIZE);
  }

  private Optional<Boolean> getOptionalBoolean(char key) {
    return Optional.of(key).map(TRUE::equals);
  }

  private OptionalInt getOptionalInt(char key) {
    final Long value = get(key);
    return value == null ? OptionalInt.empty() : OptionalInt.of((int) (long) value);
  }

  private OptionalLong getOptionalLong(char key) {
    final Long value = get(key);
    return value == null ? OptionalLong.empty() : OptionalLong.of(value);
  }

  // TODO: validation

  @Override
  public Long put(char key, Long value) {
    return super.put(key, value);
  }

  public Http2Settings headerTableSize(long headerTableSize) {
    put(HEADER_TABLE_SIZE, Long.valueOf(headerTableSize));
    return this;
  }

  public Http2Settings enablePush(boolean enablePush) {
    put(ENABLE_PUSH, enablePush ? TRUE : FALSE);
    return this;
  }

  public Http2Settings maxConcurrentStreams(long maxConcurrentStreams) {
    put(MAX_CONCURRENT_STREAMS, Long.valueOf(maxConcurrentStreams));
    return this;
  }

  public Http2Settings initialWindowSize(int initialWindowSize) {
    put(INITIAL_WINDOW_SIZE, Long.valueOf(initialWindowSize));
    return this;
  }

  public Http2Settings maxFrameSize(int maxFrameSize) {
    put(MAX_FRAME_SIZE, Long.valueOf(maxFrameSize));
    return this;
  }

  public Http2Settings maxHeaderListSize(long maxHeaderListSize) {
    put(MAX_HEADER_LIST_SIZE, Long.valueOf(maxHeaderListSize));
    return this;
  }

  @Override
  protected String keyToString(char key) {
    switch (key) {
      case HEADER_TABLE_SIZE:
        return "HEADER_TABLE_SIZE";
      case ENABLE_PUSH:
        return "ENABLE_PUSH";
      case MAX_CONCURRENT_STREAMS:
        return "MAX_CONCURRENT_STREAMS";
      case INITIAL_WINDOW_SIZE:
        return "INITIAL_WINDOW_SIZE";
      case MAX_FRAME_SIZE:
        return "MAX_FRAME_SIZE";
      case MAX_HEADER_LIST_SIZE:
        return "MAX_HEADER_LIST_SIZE";
      default:
        return super.keyToString(key);
    }
  }
}
