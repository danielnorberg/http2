package io.norberg.http2;

import io.netty.buffer.ByteBuf;
import java.util.List;


interface StreamWriter<CTX, STREAM extends Http2Stream> {

  int estimateInitialHeadersFrameSize(final CTX ctx, STREAM stream) throws Http2Exception;

  default int estimateDataFrameSize(final CTX ctx, STREAM stream, int payloadSize) throws Http2Exception {
    return Http2WireFormat.FRAME_HEADER_SIZE + payloadSize;
  }

  int estimateTrailerFrameSize(CTX ctx, STREAM stream);

  ByteBuf writeStart(final CTX ctx, int bufferSize) throws Http2Exception;

  void writeDataFrame(final CTX ctx, final ByteBuf buf, STREAM stream, int payloadSize, boolean endOfStream)
      throws Http2Exception;

  void writeInitialHeadersFrame(final CTX ctx, ByteBuf buf, STREAM stream, boolean endOfStream) throws Http2Exception;

  void writeTrailerFrames(final CTX ctx, ByteBuf buf, STREAM stream) throws Http2Exception;

  void writeEnd(final CTX ctx, ByteBuf buf, List<STREAM> writtenStreams) throws Http2Exception;

  void streamEnd(STREAM stream);
}
