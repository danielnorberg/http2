package io.norberg.h2client;

import io.netty.util.AsciiString;

class Http2Protocol {

  static final int DEFAULT_LOCAL_WINDOW_SIZE = 1024 * 1024 * 128;
  static final int DEFAULT_INITIAL_WINDOW_SIZE = 65535; // 2^16 - 1

  static final AsciiString CLIENT_PREFACE =
      AsciiString.of("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n");
}
