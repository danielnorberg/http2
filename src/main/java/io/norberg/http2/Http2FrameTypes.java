package io.norberg.http2;

class Http2FrameTypes {

  static final byte DATA = 0x0;
  static final byte HEADERS = 0x1;
  static final byte PRIORITY = 0x2;
  static final byte RST_STREAM = 0x3;
  static final byte SETTINGS = 0x4;
  static final byte PUSH_PROMISE = 0x5;
  static final byte PING = 0x6;
  static final byte GOAWAY = 0x7;
  static final byte WINDOW_UPDATE = 0x8;
  static final byte CONTINUATION = 0x9;

  static String toString(short type) {
    switch (type) {
      case DATA:
        return "DATA";
      case HEADERS:
        return "HEADERS";
      case PRIORITY:
        return "PRIORITY";
      case RST_STREAM:
        return "RST_STREAM";
      case SETTINGS:
        return "SETTINGS";
      case PUSH_PROMISE:
        return "PUSH_PROMISE";
      case PING:
        return "PING";
      case GOAWAY:
        return "GOAWAY";
      case WINDOW_UPDATE:
        return "WINDOW_UPDATE";
      case CONTINUATION:
        return "CONTINUATION";
      default:
        throw new IllegalArgumentException();
    }
  }

}
