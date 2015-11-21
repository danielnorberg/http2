/**
 * Copyright (C) 2015 Spotify AB
 */

package io.norberg.h2client;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameListener;
import io.netty.handler.codec.http2.Http2FrameTypes;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Settings;

import static io.netty.handler.codec.http2.Http2CodecUtil.FRAME_HEADER_LENGTH;
import static io.netty.handler.codec.http2.Http2CodecUtil.INT_FIELD_LENGTH;
import static io.netty.handler.codec.http2.Http2CodecUtil.PING_FRAME_PAYLOAD_LENGTH;
import static io.netty.handler.codec.http2.Http2CodecUtil.SETTING_ENTRY_LENGTH;
import static io.netty.handler.codec.http2.Http2Flags.ACK;
import static io.netty.handler.codec.http2.Http2Flags.END_HEADERS;
import static io.netty.handler.codec.http2.Http2Flags.END_STREAM;
import static io.netty.handler.codec.http2.Http2Flags.PADDED;
import static io.netty.handler.codec.http2.Http2Flags.PRIORITY;
import static java.util.Objects.requireNonNull;

class Http2FrameReader {

  private final HpackDecoder hpackDecoder;

  private int length = -1;
  private short type;
  private short flags;
  private int streamId;
  private final HeadersAssembler headersAssembler = new HeadersAssembler();

  Http2FrameReader(final HpackDecoder hpackDecoder) {
    this.hpackDecoder = requireNonNull(hpackDecoder, "hpackDecoder");
  }

  void readFrames(final ChannelHandlerContext ctx, final ByteBuf in, final Http2FrameListener listener)
      throws Http2Exception {

    while (true) {

      // Read frame header
      if (length == -1) {
        if (in.readableBytes() < FRAME_HEADER_LENGTH) {
          return;
        }

        // Read frame header fields
        length = in.readUnsignedMedium();
        type = in.readUnsignedByte();
        flags = in.readUnsignedByte();
        streamId = readInt31(in);

        // TODO: validate
      }

      // Wait for the frame payload
      if (in.readableBytes() < length) {
        return;
      }

      // Read payload
      final int mark = in.readerIndex();
      switch (type) {
        case Http2FrameTypes.DATA:
          readDataFrame(ctx, in, listener);
          break;
        case Http2FrameTypes.HEADERS:
          readHeadersFrame(ctx, in, listener);
          break;
        case Http2FrameTypes.PRIORITY:
          readPriorityFrame(ctx, in, listener);
          break;
        case Http2FrameTypes.RST_STREAM:
          readRstStreamFrame(ctx, in, listener);
          break;
        case Http2FrameTypes.SETTINGS:
          readSettingsFrame(ctx, in, listener);
          break;
        case Http2FrameTypes.PUSH_PROMISE:
          readPushPromiseFrame(ctx, in, listener);
          break;
        case Http2FrameTypes.PING:
          readPingFrame(ctx, in, listener);
          break;
        case Http2FrameTypes.GO_AWAY:
          readGoAwayFrame(ctx, in, listener);
          break;
        case Http2FrameTypes.WINDOW_UPDATE:
          readWindowUpdateFrame(ctx, in, listener);
          break;
        case Http2FrameTypes.CONTINUATION:
          readContinuationFrame(ctx, in, listener);
          break;
        default:
          // Discard unknown frame types
          in.skipBytes(length);
      }

      // Move on to next frame
      in.readerIndex(mark + length);
      length = -1;
    }
  }

  private int readInt31(final ByteBuf in) {
    return in.readInt() & 0x7FFFFFFF;
  }

  private boolean readFlag(final short flag) {
    return (flags & flag) != 0;
  }

  private short readPadding(final ByteBuf in) {
    return readFlag(PADDED) ? in.readUnsignedByte() : 0;
  }

  private void readDataFrame(final ChannelHandlerContext ctx, final ByteBuf in, final Http2FrameListener listener)
      throws Http2Exception {
    final short padding = readPadding(in);
    final int dataLength = length - padding;
    final int writerMark = in.writerIndex();
    final boolean endOfStream = readFlag(END_STREAM);
    in.writerIndex(in.readerIndex() + dataLength);
    listener.onDataRead(ctx, streamId, in, padding, endOfStream);
    in.writerIndex(writerMark);
  }

  private void readHeadersFrame(final ChannelHandlerContext ctx, final ByteBuf in, final Http2FrameListener listener)
      throws Http2Exception {
    if (!readFlag(END_HEADERS)) {
      throw new UnsupportedOperationException("TODO");
    }
    final boolean hasPriority = readFlag(PRIORITY);
    if (hasPriority) {
      readHeadersFrameWithPriority(ctx, in, listener);
    } else {
      readHeadersFrameWithoutPriority(ctx, in, listener);
    }
  }

  private void readHeadersFrameWithPriority(final ChannelHandlerContext ctx, final ByteBuf in,
                                            final Http2FrameListener listener) throws Http2Exception {
    final short padding = readPadding(in);
    final long word = in.readUnsignedInt();
    final boolean exclusive = (word & 0x8000000L) != 0;
    final int streamDependency = (int) (word & 0x7FFFFFFL);
    final short weight = in.readUnsignedByte();
    final int fieldsLength = (readFlag(PADDED) ? 1 : 0) + // padding
                             INT_FIELD_LENGTH + // stream dependency
                             1; // weight
    final int blockLength = length - fieldsLength - padding;
    final int writerMark = in.writerIndex();
    in.writerIndex(in.readerIndex() + blockLength);
    final Http2Headers headers = decodeHeaders(in);
    in.writerIndex(writerMark);
    final boolean endOfStream = readFlag(END_STREAM);
    listener.onHeadersRead(ctx, streamId, headers, streamDependency, weight, exclusive, padding, endOfStream);

  }

  private void readHeadersFrameWithoutPriority(final ChannelHandlerContext ctx, final ByteBuf in,
                                               final Http2FrameListener listener) throws Http2Exception {
    final short padding = readPadding(in);
    final int blockLength = length - (readFlag(PADDED) ? 1 : 0) - padding;
    final int writerMark = in.writerIndex();
    in.writerIndex(in.readerIndex() + blockLength);
    final Http2Headers headers = decodeHeaders(in);
    in.writerIndex(writerMark);
    final boolean endOfStream = readFlag(END_STREAM);
    listener.onHeadersRead(ctx, streamId, headers, padding, endOfStream);
  }

  private void readPriorityFrame(final ChannelHandlerContext ctx, final ByteBuf in, final Http2FrameListener listener) {
    // TODO
  }

  private void readRstStreamFrame(final ChannelHandlerContext ctx, final ByteBuf in,
                                  final Http2FrameListener listener) {
    // TODO
  }

  private void readSettingsFrame(final ChannelHandlerContext ctx, final ByteBuf in, final Http2FrameListener listener)
      throws Http2Exception {
    if (readFlag(ACK)) {
      listener.onSettingsAckRead(ctx);
      return;
    }
    final Http2Settings settings = new Http2Settings();
    final int n = length / SETTING_ENTRY_LENGTH;
    for (int i = 0; i < n; i++) {
      final int identifier = in.readUnsignedShort();
      final long value = in.readUnsignedInt();
      settings.put((char) identifier, Long.valueOf(value));
    }
    listener.onSettingsRead(ctx, settings);
  }

  private void readPushPromiseFrame(final ChannelHandlerContext ctx, final ByteBuf in,
                                    final Http2FrameListener listener) throws Http2Exception {
    // TODO
  }

  private void readPingFrame(final ChannelHandlerContext ctx, final ByteBuf in, final Http2FrameListener listener)
      throws Http2Exception {
    final int writerMark = in.writerIndex();
    in.writerIndex(in.readerIndex() + PING_FRAME_PAYLOAD_LENGTH);
    listener.onPingRead(ctx, in);
    in.writerIndex(writerMark);
  }

  private void readGoAwayFrame(final ChannelHandlerContext ctx, final ByteBuf in, final Http2FrameListener listener)
      throws Http2Exception {
    final int lastStreamId = readInt31(in);
    final long errorCode = in.readUnsignedInt();
    final int debugLength = length - 2 * INT_FIELD_LENGTH;
    final int writerMark = in.writerIndex();
    in.writerIndex(in.readerIndex() + debugLength);
    listener.onGoAwayRead(ctx, lastStreamId, errorCode, in);
    in.writerIndex(writerMark);
  }

  private void readWindowUpdateFrame(final ChannelHandlerContext ctx, final ByteBuf in,
                                     final Http2FrameListener listener) throws Http2Exception {
    final int windowSizeIncrement = readInt31(in);
    listener.onWindowUpdateRead(ctx, streamId, windowSizeIncrement);
  }

  private void readContinuationFrame(final ChannelHandlerContext ctx, final ByteBuf in,
                                     final Http2FrameListener listener) throws Http2Exception {
    throw new UnsupportedOperationException("TODO");
  }

  private Http2Headers decodeHeaders(final ByteBuf in) throws HpackDecodingException {
    headersAssembler.reset();
    hpackDecoder.decode(in, headersAssembler);
    return headersAssembler.headers();
  }

}
