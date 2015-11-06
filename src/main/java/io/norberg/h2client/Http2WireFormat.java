/**
 * Copyright (C) 2015 Spotify AB
 */

package io.norberg.h2client;

import io.netty.buffer.ByteBuf;
import io.netty.util.ByteString;

class Http2WireFormat {

  static final ByteString CLIENT_PREFACE =
      ByteString.fromAscii("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n");

  private static final int FRAME_LENGTH_OFFSET = 0;
  private static final int FRAME_TYPE_OFFSET = FRAME_LENGTH_OFFSET + 3;
  private static final int FRAME_FLAGS_OFFSET = FRAME_TYPE_OFFSET + 1;
  private static final int FRAME_STREAM_ID_OFFSET = FRAME_FLAGS_OFFSET + 1;

  static void writeFrameHeader(final ByteBuf buf, final int offset, final int length,
                               final int type, final int flags, final int streamId) {
    buf.setMedium(offset + FRAME_LENGTH_OFFSET, length);
    buf.setByte(offset + FRAME_TYPE_OFFSET, type);
    buf.setByte(offset + FRAME_FLAGS_OFFSET, flags);
    buf.setInt(offset + FRAME_STREAM_ID_OFFSET, streamId);
  }
}
