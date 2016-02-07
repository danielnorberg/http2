package io.norberg.h2client;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http2.Http2Exception;

interface StreamWriter<CTX, STREAM extends Stream> {

  int estimateInitialHeadersFrameSize(final CTX ctx, STREAM stream) throws Http2Exception;

  default int estimateDataFrameSize(final CTX ctx, STREAM stream, int payloadSize) throws Http2Exception {
    return Http2WireFormat.FRAME_HEADER_SIZE + payloadSize;
  }

  ByteBuf writeStart(final CTX ctx, int bufferSize) throws Http2Exception;

  void writeDataFrame(final CTX ctx, final ByteBuf buf, STREAM stream, int payloadSize, boolean endOfStream)
      throws Http2Exception;

  void writeInitialHeadersFrame(final CTX ctx, ByteBuf buf, STREAM stream, boolean endOfStream) throws Http2Exception;

  void writeEnd(final CTX ctx, ByteBuf buf) throws Http2Exception;

  void streamEnd(STREAM stream);
}
