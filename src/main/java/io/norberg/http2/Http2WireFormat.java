package io.norberg.http2;

import static io.norberg.http2.Http2FrameTypes.SETTINGS;
import static io.norberg.http2.Http2FrameTypes.WINDOW_UPDATE;

import io.netty.buffer.ByteBuf;
import io.netty.util.AsciiString;

class Http2WireFormat {

  static final AsciiString CLIENT_PREFACE =
      AsciiString.of("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n");

  static final int FRAME_HEADER_SIZE = 9;

  static final int FRAME_LENGTH_OFFSET = 0;
  static final int FRAME_TYPE_OFFSET = FRAME_LENGTH_OFFSET + 3;
  static final int FRAME_FLAGS_OFFSET = FRAME_TYPE_OFFSET + 1;
  static final int FRAME_STREAM_ID_OFFSET = FRAME_FLAGS_OFFSET + 1;

  static final int SHORT_FIELD_LENGTH = 2;
  static final int INT_FIELD_LENGTH = 4;
  static final int SETTING_ENTRY_LENGTH = SHORT_FIELD_LENGTH + INT_FIELD_LENGTH;
  static final int FRAME_HEADER_LENGTH = 9;

  static final int WINDOW_UPDATE_FRAME_LENGTH = FRAME_HEADER_LENGTH + INT_FIELD_LENGTH;
  static final int PING_FRAME_PAYLOAD_LENGTH = 8;


  static void writeFrameHeader(final ByteBuf buf, final int offset, final int length,
      final int type, final int flags, final int streamId) {
    buf.setMedium(offset + FRAME_LENGTH_OFFSET, length);
    buf.setByte(offset + FRAME_TYPE_OFFSET, type);
    buf.setByte(offset + FRAME_FLAGS_OFFSET, flags);
    buf.setInt(offset + FRAME_STREAM_ID_OFFSET, streamId);
  }

  static void writeWindowUpdate(final ByteBuf buf, final int streamId, final int sizeIncrement) {
    final int offset = buf.writerIndex();
    assert buf.writableBytes() >= WINDOW_UPDATE_FRAME_LENGTH;
    writeFrameHeader(buf, offset, INT_FIELD_LENGTH, WINDOW_UPDATE, 0, streamId);
    buf.writerIndex(offset + FRAME_HEADER_LENGTH);
    buf.writeInt(sizeIncrement);
  }

  static void writeSettings(final ByteBuf buf, final Http2Settings settings) {
    final int length = SETTING_ENTRY_LENGTH * settings.size();
    final int offset = buf.writerIndex();
    writeFrameHeader(buf, offset, length, SETTINGS, 0, 0);
    buf.writerIndex(offset + FRAME_HEADER_LENGTH);
    settings.forEach((key, value) -> {
      buf.writeShort(key);
      buf.writeInt(value.intValue());
    });
  }

  static int settingsFrameLength(final Http2Settings settings) {
    final int length = SETTING_ENTRY_LENGTH * settings.size();
    return FRAME_HEADER_LENGTH + length;
  }

//  static int headersPayloadSize(final Http2Headers headers) {
//    int size = 0;
//    for (final Map.Entry<CharSequence, CharSequence> header : headers) {
//      size += Http2Header.size(header.getKey(), header.getValue());
//    }
//    return size;
//  }

  static int headersPayloadSize(final Http2Message<?> message) {
    int size = 0;
    final int n = message.numInitialHeaders();
    for (int i = 0; i < n; i++) {
      size += Http2Header.size(message.headerName(i), message.headerValue(i));
    }
    return size;
  }

  static boolean isValidHeaderName(final AsciiString name) {
    final int offset = name.arrayOffset();
    final byte[] bytes = name.array();
    final int n = name.length() + offset;
    for (int i = offset; i < n; i++) {
      final byte c = bytes[i];
      if (c >= 'A' && c <= 'Z') {
        return false;
      }
    }
    return true;
  }

  static int trailersPayloadSize(Http2Message<?> message) {
    int size = 0;
    final int n = message.numHeaders();
    for (int i = message.trailingHeaderIndex(); i < n; i++) {
      size += Http2Header.size(message.headerName(i), message.headerValue(i));
    }
    return size;
  }
}
