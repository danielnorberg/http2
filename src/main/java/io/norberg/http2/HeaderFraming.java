package io.norberg.http2;

import static io.netty.handler.codec.http2.Http2CodecUtil.FRAME_HEADER_LENGTH;
import static io.netty.handler.codec.http2.Http2Flags.END_HEADERS;
import static io.netty.handler.codec.http2.Http2Flags.END_STREAM;
import static io.netty.handler.codec.http2.Http2FrameTypes.CONTINUATION;
import static io.netty.handler.codec.http2.Http2FrameTypes.HEADERS;
import static io.norberg.http2.Http2WireFormat.writeFrameHeader;

import io.netty.buffer.ByteBuf;

class HeaderFraming {

  /**
   * Slice up a header block into {@code frameSize} sized frames and write HEADER and CONTINUATION frame headers.
   *
   * It is assumed that frame block is laid out in the buffer with space for the HEADER frame header prepended. The
   * frame payloads are moved in the buffer in-place, from back to front.
   */
  static int frameHeaderBlock(final ByteBuf buf, final int headerIndex, final int blockSize,
      final int frameSize, final boolean endOfStream, final int streamId) {

    final int payloadIndex = headerIndex + FRAME_HEADER_LENGTH;

    final int flags = endOfStream ? END_STREAM : 0;

    if (blockSize <= frameSize) {
      // No need for CONTINUATION frames. Just write the HEADER frame header and we're done.
      // This is assumed to be the most common case by far.
      writeFrameHeader(buf, headerIndex, blockSize, HEADERS, flags | END_HEADERS, streamId);
      return headerIndex + FRAME_HEADER_LENGTH + blockSize;
    }

    // Write the HEADER frame header
    writeFrameHeader(buf, headerIndex, frameSize, HEADERS, flags, streamId);

    // Figure out how many CONTINUATION frames we will write
    final int continuationSize = blockSize - frameSize;
    final int leadingFrames = (continuationSize - 1) / frameSize;
    final int lastFrameSize = continuationSize - (leadingFrames * frameSize);
    final int continuationIndex = payloadIndex + frameSize;

    // Set up destination and source indices starting at the final CONTINUATION frame
    int dstIndex = continuationIndex + (leadingFrames * (FRAME_HEADER_LENGTH + frameSize)) + FRAME_HEADER_LENGTH;
    int srcIndex = continuationIndex + (leadingFrames * frameSize);

    // Write final CONTINUATION frame
    final int nextWriterIndex = dstIndex + lastFrameSize;
    buf.setBytes(dstIndex, buf, srcIndex, lastFrameSize);
    dstIndex -= FRAME_HEADER_LENGTH;
    writeFrameHeader(buf, dstIndex, lastFrameSize, CONTINUATION, END_HEADERS, streamId);

    // Work our way backwards through the frames, moving frame payloads into place as we go
    for (int i = 0; i < leadingFrames; i++) {
      srcIndex -= frameSize;
      dstIndex -= frameSize;
      buf.setBytes(dstIndex, buf, srcIndex, frameSize);
      dstIndex -= FRAME_HEADER_LENGTH;
      writeFrameHeader(buf, dstIndex, frameSize, CONTINUATION, 0, streamId);
    }
    return nextWriterIndex;
  }
}
