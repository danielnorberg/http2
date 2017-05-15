package io.norberg.http2;

class Http2Flags {

  static final short ACK = 0x1;

  static final short END_STREAM = 0x1;
  static final short END_HEADERS = 0x4;
  static final short PADDED = 0x8;
  static final short PRIORITY = 0x20;
}
