package io.norberg.http2;

import io.netty.util.AsciiString;

class PseudoHeaders {

  public static final AsciiString METHOD = AsciiString.of(":method");
  public static final AsciiString SCHEME = AsciiString.of(":scheme");
  public static final AsciiString AUTHORITY = AsciiString.of(":authority");
  public static final AsciiString PATH = AsciiString.of(":path");
  public static final AsciiString STATUS = AsciiString.of(":status");
}
