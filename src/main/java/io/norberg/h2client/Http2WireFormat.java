/**
 * Copyright (C) 2015 Spotify AB
 */

package io.norberg.h2client;

import java.util.Map;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.util.AsciiString;

import static io.netty.handler.codec.http2.Http2CodecUtil.FRAME_HEADER_LENGTH;
import static io.netty.handler.codec.http2.Http2CodecUtil.INT_FIELD_LENGTH;
import static io.netty.handler.codec.http2.Http2CodecUtil.SETTING_ENTRY_LENGTH;
import static io.netty.handler.codec.http2.Http2CodecUtil.WINDOW_UPDATE_FRAME_LENGTH;
import static io.netty.handler.codec.http2.Http2FrameTypes.SETTINGS;
import static io.netty.handler.codec.http2.Http2FrameTypes.WINDOW_UPDATE;

class Http2WireFormat {

  static final AsciiString CLIENT_PREFACE =
      AsciiString.of("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n");

  static final int FRAME_HEADER_SIZE = 9;

  static final int FRAME_LENGTH_OFFSET = 0;
  static final int FRAME_TYPE_OFFSET = FRAME_LENGTH_OFFSET + 3;
  static final int FRAME_FLAGS_OFFSET = FRAME_TYPE_OFFSET + 1;
  static final int FRAME_STREAM_ID_OFFSET = FRAME_FLAGS_OFFSET + 1;


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
    for (final char identifier : settings.keySet()) {
      final int value = settings.getIntValue(identifier);
      buf.writeShort(identifier);
      buf.writeInt(value);
    }
  }

  static int settingsFrameLength(final Http2Settings settings) {
    final int length = SETTING_ENTRY_LENGTH * settings.size();
    return FRAME_HEADER_LENGTH + length;
  }

  static int headersPayloadSize(final Http2Headers headers) {
    int size = 0;
    for (final Map.Entry<CharSequence, CharSequence> header : headers) {
      size += Http2Header.size(header.getKey(), header.getValue());
    }
    return size;
  }
}
