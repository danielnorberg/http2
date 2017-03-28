package io.norberg.http2;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Set;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http2.Http2Exception;

import static io.netty.handler.codec.http2.Http2Error.PROTOCOL_ERROR;
import static io.norberg.http2.Http2Protocol.DEFAULT_INITIAL_WINDOW_SIZE;
import static io.norberg.http2.Http2Protocol.DEFAULT_MAX_FRAME_SIZE;
import static io.norberg.http2.Util.connectionError;

class FlowController<CTX, STREAM extends Http2Stream> {

  private final List<STREAM> newStreams = new ArrayList<>();
  private final Deque<STREAM> connectionWindowBlockedStreams = new ArrayDeque<>();
  private final List<STREAM> streamWindowUpdatedStreams = new ArrayList<>();

  private int remoteInitialStreamWindow;
  private int remoteConnectionWindow;
  private int remoteMaxFramePayloadSize = DEFAULT_MAX_FRAME_SIZE;

  private boolean remoteConnectionWindowUpdated;

  FlowController() {
    this(DEFAULT_INITIAL_WINDOW_SIZE, DEFAULT_INITIAL_WINDOW_SIZE);
  }

  FlowController(final int remoteConnectionWindow, final int remoteInitialStreamWindow) {
    this.remoteInitialStreamWindow = remoteInitialStreamWindow;
    this.remoteConnectionWindow = remoteConnectionWindow;
  }

  int remoteConnectionWindow() {
    return remoteConnectionWindow;
  }

  int remoteInitialStreamWindow() {
    return remoteInitialStreamWindow;
  }

  private int prepareDataFrames(final StreamWriter<CTX, STREAM> streamWriter, final STREAM stream, final CTX ctx)
      throws Http2Exception {
    final ByteBuf data = stream.data;
    if (data == null || !data.isReadable()) {
      return 0;
    }
    final int dataSize = data.readableBytes();
    final int window = min(remoteConnectionWindow, stream.remoteWindow);
    if (window == 0) {
      stream.fragmentSize = 0;
      return 0;
    }
    final int fragmentSize = min(dataSize, window);
    stream.fragmentSize = fragmentSize;
    stream.remoteWindow -= fragmentSize;
    remoteConnectionWindow -= fragmentSize;

    // Multiple frames?
    final int framedSize;
    if (fragmentSize > remoteMaxFramePayloadSize) {
      framedSize = estimateMultipleFrameSize(streamWriter, ctx, stream, fragmentSize);
    } else {
      framedSize = estimateSingleFrameSize(streamWriter, ctx, stream, fragmentSize);
    }

    return framedSize;
  }

  private int estimateSingleFrameSize(final StreamWriter<CTX, STREAM> streamWriter, final CTX ctx, final STREAM stream,
                                      final int fragmentSize) throws Http2Exception {
    stream.frames = 1;
    return streamWriter.estimateDataFrameSize(ctx, stream, fragmentSize);
  }

  private int estimateMultipleFrameSize(final StreamWriter<CTX, STREAM> streamWriter, final CTX ctx,
                                        final STREAM stream, final int fragmentSize)
      throws Http2Exception {
    int frames = fragmentSize / remoteMaxFramePayloadSize;
    final int fullFrameSize = streamWriter.estimateDataFrameSize(ctx, stream, remoteMaxFramePayloadSize);
    final int totalFullFramePayloadSize = frames * remoteMaxFramePayloadSize;
    final int remainingFragmentSize = fragmentSize - totalFullFramePayloadSize;
    int totalFramedSize = fullFrameSize * frames;
    if (remainingFragmentSize > 0) {
      frames += 1;
      totalFramedSize += streamWriter.estimateDataFrameSize(ctx, stream, remainingFragmentSize);
    }
    stream.frames = frames;
    return totalFramedSize;
  }

  void flush(final CTX ctx, final StreamWriter<CTX, STREAM> writer) throws Http2Exception {

    // Prepare frames
    final int bufferSize = prepare(ctx, writer);

    // Write frames
    writeFrames(ctx, writer, bufferSize);

    streamWindowUpdatedStreams.clear();
    newStreams.clear();
  }

  private void writeFrames(final CTX ctx, final StreamWriter<CTX, STREAM> writer, final int bufferSize)
      throws Http2Exception {

    final ByteBuf buf = bufferSize == 0
                        ? null
                        : writer.writeStart(ctx, bufferSize);

    // Write data frames for streams that had window updates
    if (!streamWindowUpdatedStreams.isEmpty()) {
      writeWindowUpdatedStreams(ctx, writer, buf);
    }

    // Write data frames for streams that were blocking on a connection window update
    if (remoteConnectionWindowUpdated) {
      writeConnectionWindowBlockedStreams(ctx, writer, buf);
    }

    // Write headers and data frames for new outgoing streams
    if (!newStreams.isEmpty()) {
      writeNewStreams(ctx, writer, buf);
    }

    // Finish write if there was anything to write
    if (buf != null) {
      writer.writeEnd(ctx, buf);
    }
  }

  private void writeNewStreams(final CTX ctx, final StreamWriter<CTX, STREAM> writer, final ByteBuf buf)
      throws Http2Exception {

    // Was the remote connection window exhausted?
    final boolean remoteConnectionWindowExhausted = (remoteConnectionWindow == 0);

    for (int i = 0; i < newStreams.size(); i++) {
      final STREAM stream = newStreams.get(i);
      final boolean hasContent = stream.data != null && stream.data.isReadable();

      // Write headers
      writer.writeInitialHeadersFrame(ctx, buf, stream, !hasContent);

      // Any data?
      if (!hasContent) {
        writer.streamEnd(stream);
        continue;
      }

      // Write data
      final int size = stream.fragmentSize;
      if (size > 0) {
        final boolean endOfStream = (stream.data.readableBytes() == size);
        writeDataFrames(writer, ctx, buf, stream, size, endOfStream);
        if (endOfStream) {
          writer.streamEnd(stream);
        }
      }

      // Check if the connection window was exhausted
      if (remoteConnectionWindowExhausted &&
          stream.data.readableBytes() > 0 &&
          stream.remoteWindow > 0) {
        stream.pending = true;
        connectionWindowBlockedStreams.add(stream);
      }
    }
  }

  private void writeConnectionWindowBlockedStreams(final CTX ctx, final StreamWriter<CTX, STREAM> writer,
                                                   final ByteBuf buf) throws Http2Exception {

    // Was the remote connection window exhausted?
    final boolean remoteConnectionWindowExhausted = (remoteConnectionWindow == 0);

    while (true) {
      final STREAM stream = connectionWindowBlockedStreams.peekFirst();
      if (stream == null) {
        break;
      }

      assert stream.data != null && stream.data.isReadable();

      // Bail if the connection window was exhausted before this stream could get any quota.
      // All following streams in the queue will still be blocked on the connection window.
      final int size = stream.fragmentSize;
      if (size == 0) {
        break;
      }

      // Write data
      final boolean endOfStream = (stream.data.readableBytes() == size);
      writeDataFrames(writer, ctx, buf, stream, size, endOfStream);
      if (endOfStream) {
        writer.streamEnd(stream);
      }

      // Bail if the connection window was exhausted by this stream.
      // All following streams in the queue will still be blocked on the connection window.
      if (remoteConnectionWindowExhausted &&
          stream.data.readableBytes() > 0 &&
          stream.remoteWindow > 0) {
        break;
      }

      // This stream is no longer blocking on the connection window. Remove it from the queue.
      stream.pending = false;
      connectionWindowBlockedStreams.removeFirst();
    }

    remoteConnectionWindowUpdated = false;
  }

  private void writeWindowUpdatedStreams(final CTX ctx, final StreamWriter<CTX, STREAM> writer, final ByteBuf buf)
      throws Http2Exception {

    // Was the remote connection window exhausted?
    final boolean remoteConnectionWindowExhausted = (remoteConnectionWindow == 0);

    for (int i = 0; i < streamWindowUpdatedStreams.size(); i++) {
      final STREAM stream = streamWindowUpdatedStreams.get(i);

      assert stream.data != null && stream.data.isReadable();

      // Write data frames
      final int size = stream.fragmentSize;
      final boolean endOfStream = (stream.data.readableBytes() == size);
      if (size > 0) {
        writeDataFrames(writer, ctx, buf, stream, size, endOfStream);
        if (endOfStream) {
          writer.streamEnd(stream);
        }
      }

      // Check if the stream was blocked on an exhausted connection window.
      if (remoteConnectionWindowExhausted &&
          stream.data.readableBytes() > 0 &&
          stream.remoteWindow > 0) {
        stream.pending = true;
        connectionWindowBlockedStreams.add(stream);
      }
    }
  }

  private int prepare(final CTX ctx, final StreamWriter<CTX, STREAM> writer) throws Http2Exception {
    int size = 0;

    // Prepare data frames for all streams that had window updates
    for (int i = 0; i < streamWindowUpdatedStreams.size(); i++) {
      final STREAM stream = streamWindowUpdatedStreams.get(i);
      stream.pending = false;
      size += prepareDataFrames(writer, stream, ctx);
    }

    // Prepare data frames for all streams that were blocking on a connection window update
    if (remoteConnectionWindowUpdated) {
      for (final STREAM stream : connectionWindowBlockedStreams) {
        final int n = prepareDataFrames(writer, stream, ctx);
        if (n == 0) {
          break;
        }
        size += n;
      }
    }

    // Prepare headers and data frames for new outgoing streams
    for (int i = 0; i < newStreams.size(); i++) {
      final STREAM stream = newStreams.get(i);
      stream.pending = false;
      size += writer.estimateInitialHeadersFrameSize(ctx, stream);
      size += prepareDataFrames(writer, stream, ctx);
    }

    return size;
  }

  private void writeDataFrames(final StreamWriter<CTX, STREAM> writer, final CTX ctx, final ByteBuf buf,
                               final STREAM stream, final int size,
                               final boolean endOfStream) throws Http2Exception {
    assert size >= 0;
    int remaining = size;
    final int fullFrames = stream.frames - 1;
    for (int i = 0; i < fullFrames; i++) {
      writer.writeDataFrame(ctx, buf, stream, remoteMaxFramePayloadSize, false);
      remaining -= remoteMaxFramePayloadSize;
    }
    writer.writeDataFrame(ctx, buf, stream, remaining, endOfStream);
  }

  void start(final STREAM stream) {
    stream.started = true;
    stream.pending = true;
    stream.remoteWindow = remoteInitialStreamWindow;
    newStreams.add(stream);
  }

  public void stop(final STREAM stream) {
    final Set<STREAM> s = Collections.singleton(stream);
    newStreams.removeAll(s);
    connectionWindowBlockedStreams.removeAll(s);
    streamWindowUpdatedStreams.removeAll(s);
  }

  void remoteConnectionWindowUpdate(final int sizeIncrement) throws Http2Exception {
    if (sizeIncrement <= 0) {
      throw connectionError(PROTOCOL_ERROR, "Illegal window size increment: %d", sizeIncrement);
    }
    remoteConnectionWindow += sizeIncrement;
    remoteConnectionWindowUpdated = true;
  }

  void remoteInitialStreamWindowSizeUpdate(final int size, final Iterable<STREAM> streams) throws Http2Exception {
    final int delta = size - remoteInitialStreamWindow;
    for (final STREAM stream : streams) {
      if (stream.started) {
        remoteStreamWindowUpdate0(stream, delta);
      }
    }
    remoteInitialStreamWindow = size;
  }

  void remoteStreamWindowUpdate(final STREAM stream, final int windowSizeIncrement) throws Http2Exception {
    if (windowSizeIncrement <= 0) {
      throw connectionError(PROTOCOL_ERROR, "Illegal window size increment: %d", windowSizeIncrement);
    }
    remoteStreamWindowUpdate0(stream, windowSizeIncrement);
  }

  private void remoteStreamWindowUpdate0(final STREAM stream, final int delta) {
    stream.remoteWindow += delta;
    if (stream.data == null || !stream.data.isReadable()) {
      return;
    }
    if (stream.remoteWindow > 0 && !stream.pending) {
      stream.pending = true;
      streamWindowUpdatedStreams.add(stream);
    }
  }

  void remoteMaxFrameSize(final int remoteMaxFrameSize) {
    this.remoteMaxFramePayloadSize = remoteMaxFrameSize;
  }

  private static int min(final int a, final int b, final int c) {
    return min(min(a, b), c);
  }

  private static int min(final int a, final int b) {
    return Math.min(a, b);
  }
}
