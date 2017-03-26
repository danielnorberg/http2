package io.norberg.http2;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameTypes;
import io.netty.handler.codec.http2.Http2Settings;

import static io.netty.handler.codec.http2.Http2CodecUtil.FRAME_HEADER_LENGTH;
import static io.netty.handler.codec.http2.Http2CodecUtil.INT_FIELD_LENGTH;
import static io.netty.handler.codec.http2.Http2CodecUtil.PING_FRAME_PAYLOAD_LENGTH;
import static io.netty.handler.codec.http2.Http2CodecUtil.SETTING_ENTRY_LENGTH;
import static io.netty.handler.codec.http2.Http2Error.PROTOCOL_ERROR;
import static io.netty.handler.codec.http2.Http2Flags.ACK;
import static io.netty.handler.codec.http2.Http2Flags.END_HEADERS;
import static io.netty.handler.codec.http2.Http2Flags.END_STREAM;
import static io.netty.handler.codec.http2.Http2Flags.PADDED;
import static io.netty.handler.codec.http2.Http2Flags.PRIORITY;
import static io.norberg.http2.Util.connectionError;
import static java.util.Objects.requireNonNull;



class Http2FrameReader implements HpackDecoder.Listener, AutoCloseable {

  private final HpackDecoder hpackDecoder;
  private final Http2FrameListener listener;

  private int length = -1;
  private short type;
  private short flags;
  private int streamId;

  // For handling CONTINUATION frames
  private ByteBuf headersBlock;
  private short headersFlags;

  Http2FrameReader(final HpackDecoder hpackDecoder, final Http2FrameListener listener) {
    this.hpackDecoder = requireNonNull(hpackDecoder, "hpackDecoder");
    this.listener = requireNonNull(listener, "listener");
  }

  @Override
  public void close() {
    if (headersBlock != null) {
      headersBlock.release();
      headersBlock = null;
    }
  }

  void readFrames(final ChannelHandlerContext ctx, final ByteBuf in)
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

        if (headersBlock != null && type != Http2FrameTypes.CONTINUATION) {
          throw connectionError(PROTOCOL_ERROR,
              "Expected CONTINUATION frame, got %d: streamId=%d", type, streamId);
        }
      }

      // Wait for the frame payload
      if (in.readableBytes() < length) {
        return;
      }

      // Read payload
      final int mark = in.readerIndex();
      switch (type) {
        case Http2FrameTypes.DATA:
          readDataFrame(ctx, in);
          break;
        case Http2FrameTypes.HEADERS:
          readHeadersFrame(ctx, in);
          break;
        case Http2FrameTypes.PRIORITY:
          readPriorityFrame(ctx, in);
          break;
        case Http2FrameTypes.RST_STREAM:
          readRstStreamFrame(ctx, in);
          break;
        case Http2FrameTypes.SETTINGS:
          readSettingsFrame(ctx, in);
          break;
        case Http2FrameTypes.PUSH_PROMISE:
          readPushPromiseFrame(ctx, in);
          break;
        case Http2FrameTypes.PING:
          readPingFrame(ctx, in);
          break;
        case Http2FrameTypes.GO_AWAY:
          readGoAwayFrame(ctx, in);
          break;
        case Http2FrameTypes.WINDOW_UPDATE:
          readWindowUpdateFrame(ctx, in, listener);
          break;
        case Http2FrameTypes.CONTINUATION:
          readContinuationFrame(ctx, in);
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
    return readFlag(flag, flags);
  }

  private static boolean readFlag(short flag, short flags) {
    return (flags & flag) != 0;
  }

  private short readPadding(final ByteBuf in) {
    return readFlag(PADDED) ? in.readUnsignedByte() : 0;
  }

  private void readDataFrame(final ChannelHandlerContext ctx, final ByteBuf in)
      throws Http2Exception {
    final short padding = readPadding(in);
    final int dataLength = length - padding;
    final int writerMark = in.writerIndex();
    final boolean endOfStream = readFlag(END_STREAM);
    in.writerIndex(in.readerIndex() + dataLength);
    listener.onDataRead(ctx, streamId, in, padding, endOfStream);
    in.writerIndex(writerMark);
  }

  private void readHeadersFrame(final ChannelHandlerContext ctx, final ByteBuf in)
      throws Http2Exception {
    final boolean hasPriority = readFlag(PRIORITY);
    final boolean endHeaders = readFlag(END_HEADERS);
    final short padding = readPadding(in);
    final int blockLength = length - (readFlag(PADDED) ? 1 : 0) - padding;

    // Handle HEADERS + CONTINUATION
    if (!endHeaders) {
      assert headersBlock == null;
      // CONTINUATION headers are assumed to be rare and the resulting frame blocks large enough
      // that we can accept allocating a temporary cumulation buffer.
      // TODO: Piggyback on the preceding cumulation buffer instead and avoid copying?
      this.headersBlock = in.alloc().buffer(blockLength * 2);
      this.headersFlags = flags;
      in.readBytes(this.headersBlock, blockLength);
      return;
    }

    if (hasPriority) {
      readHeadersFrameWithPriority(ctx, in);
    } else {
      readHeadersFrameWithoutPriority(ctx, in);
    }
  }

  private void readHeadersFrameWithPriority(final ChannelHandlerContext ctx, final ByteBuf in) throws Http2Exception {
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
    final boolean endOfStream = readFlag(END_STREAM);
    listener.onHeadersRead(ctx, streamId, streamDependency, weight, exclusive, endOfStream);
    hpackDecoder.decode(in, this);
    listener.onHeadersEnd(ctx, streamId, endOfStream);
    in.writerIndex(writerMark);
  }

  private void readHeadersFrameWithoutPriority(final ChannelHandlerContext ctx, final ByteBuf in) throws Http2Exception {
    final short padding = readPadding(in);
    final int blockLength = length - (readFlag(PADDED) ? 1 : 0) - padding;
    final boolean endOfStream = readFlag(END_STREAM);
    final int writerMark = in.writerIndex();
    in.writerIndex(in.readerIndex() + blockLength);
    listener.onHeadersRead(ctx, streamId, endOfStream);
    hpackDecoder.decode(in, this);
    listener.onHeadersEnd(ctx, streamId, endOfStream);
    in.writerIndex(writerMark);
  }

  private void readContinuationFrame(final ChannelHandlerContext ctx, final ByteBuf in) throws Http2Exception {
    final boolean hasPriority = readFlag(headersFlags, PRIORITY);
    final boolean endOfStream = readFlag(headersFlags, END_STREAM);
    final boolean endHeaders = readFlag(END_HEADERS);

    if (headersBlock == null) {
      throw connectionError(PROTOCOL_ERROR,
          "Unexpected CONTINUATION frame: streamId=%d", streamId);
    }

    if (headersBlock.writableBytes() < length) {
      headersBlock.capacity(headersBlock.capacity() * 2);
    }
    in.readBytes(headersBlock, length);

    if (endHeaders) {
      if (hasPriority) {
        final long word = in.readUnsignedInt();
        final boolean exclusive = (word & 0x8000000L) != 0;
        final int streamDependency = (int) (word & 0x7FFFFFFL);
        final short weight = in.readUnsignedByte();
        listener.onHeadersRead(ctx, streamId, streamDependency, weight, exclusive, endOfStream);
      } else {
        listener.onHeadersRead(ctx, streamId, endOfStream);
      }
      hpackDecoder.decode(headersBlock, this);
      listener.onHeadersEnd(ctx, streamId, endOfStream);
      headersBlock.release();
      headersBlock = null;
      headersFlags = 0;
    }
  }

  private void readPriorityFrame(final ChannelHandlerContext ctx, final ByteBuf in) throws Http2Exception {
    final long w0 = in.readUnsignedInt();
    final short weight = in.readUnsignedByte();
    final int streamDependency = (int) (w0 & 0x7FFFFFFFL);
    final boolean exclusive = (w0 & 0x80000000L) != 0;
    listener.onPriorityRead(ctx, streamId, streamDependency, weight, exclusive);
  }

  private void readRstStreamFrame(final ChannelHandlerContext ctx, final ByteBuf in) throws Http2Exception {
    final long errorCode = in.readUnsignedInt();
    listener.onRstStreamRead(ctx, streamId, errorCode);
  }

  private void readSettingsFrame(final ChannelHandlerContext ctx, final ByteBuf in)
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

  private void readPushPromiseFrame(final ChannelHandlerContext ctx, final ByteBuf in) throws Http2Exception {
    final short padding = readPadding(in);
    final long w0 = in.readUnsignedInt();
    final int promisedStreamId = (int) (w0 & 0x7FFFFFFFL);
    final boolean endHeaders = readFlag(END_HEADERS);
    if (!endHeaders) {
      throw new UnsupportedOperationException("TODO");
    }
    final int blockLength = length - (readFlag(PADDED) ? 1 : 0) - padding;
    final int writerMark = in.writerIndex();
    in.writerIndex(in.readerIndex() + blockLength);
    listener.onPushPromiseRead(ctx,  streamId, promisedStreamId, null, padding);
    hpackDecoder.decode(in, this);
    listener.onPushPromiseHeadersEnd(ctx, streamId);
    in.writerIndex(writerMark);
  }

  private void readPingFrame(final ChannelHandlerContext ctx, final ByteBuf in)
      throws Http2Exception {
    final int writerMark = in.writerIndex();
    in.writerIndex(in.readerIndex() + PING_FRAME_PAYLOAD_LENGTH);
    listener.onPingRead(ctx, in);
    in.writerIndex(writerMark);
  }

  private void readGoAwayFrame(final ChannelHandlerContext ctx, final ByteBuf in)
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

  @Override
  public void header(final Http2Header header) throws Http2Exception {
    listener.onHeaderRead(header);
  }
}
