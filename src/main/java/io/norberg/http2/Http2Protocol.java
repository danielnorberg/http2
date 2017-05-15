package io.norberg.http2;

import static io.norberg.http2.Http2Error.PROTOCOL_ERROR;

import io.netty.util.AsciiString;

class Http2Protocol {

  static final int DEFAULT_HEADER_TABLE_SIZE = 4096;
  static final int DEFAULT_LOCAL_WINDOW_SIZE = 1024 * 1024 * 128;
  static final int DEFAULT_INITIAL_WINDOW_SIZE = 65535; // 2^16 - 1

  static final AsciiString CLIENT_PREFACE =
      AsciiString.of("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n");

  static final int MAX_FRAME_SIZE_LOWER_BOUND = 0x4000;
  static final int MAX_FRAME_SIZE_UPPER_BOUND = 0xffffff;
  static final int DEFAULT_MAX_FRAME_SIZE = MAX_FRAME_SIZE_LOWER_BOUND;

  static boolean isValidMaxFrameSize(final int size) {
    return size >= MAX_FRAME_SIZE_LOWER_BOUND &&
        size <= MAX_FRAME_SIZE_UPPER_BOUND;
  }

  static void validateMaxFrameSize(final int size) throws Http2Exception {
    if (!isValidMaxFrameSize(size)) {
      throw new Http2Exception(PROTOCOL_ERROR, "invalid max frame size: " + size);
    }
  }
}
