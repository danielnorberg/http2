package io.norberg.h2client;

import io.netty.buffer.ByteBuf;

interface StreamWriter {

  int estimateInitialHeadersFrameSize(Stream stream);

  default int estimateDataFrameSize(Stream stream, int payloadSize) {
    return Http2WireFormat.FRAME_HEADER_SIZE + payloadSize;
  }

  ByteBuf writeStart(int bufferSize);

  void writeDataFrame(final ByteBuf buf, Stream stream, int payloadSize, boolean endOfStream);

  void writeInitialHeadersFrame(ByteBuf buf, Stream stream, boolean endOfStream);

  void writeEnd(ByteBuf buf);
}
