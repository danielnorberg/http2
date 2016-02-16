package io.norberg.h2client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Set;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http2.Http2Exception;

import static io.netty.handler.codec.http2.Http2Error.PROTOCOL_ERROR;
import static io.norberg.h2client.Http2Protocol.DEFAULT_INITIAL_WINDOW_SIZE;
import static io.norberg.h2client.Http2Protocol.DEFAULT_MAX_FRAME_SIZE;
import static io.norberg.h2client.Util.connectionError;

class FlowController<CTX, STREAM extends Stream> {

  private final List<STREAM> startedStreams = new ArrayList<>();
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
    int bufferSize = 0;

    // Prepare data frames for all streams that had window updates
    for (int i = 0; i < streamWindowUpdatedStreams.size(); i++) {
      final STREAM stream = streamWindowUpdatedStreams.get(i);
      bufferSize += prepareDataFrames(writer, stream, ctx);
    }

    // Prepare data frames for all streams that were blocking on a connection window update
    if (remoteConnectionWindowUpdated) {
      for (final STREAM stream : connectionWindowBlockedStreams) {
        final int n = prepareDataFrames(writer, stream, ctx);
        if (n == 0) {
          break;
        }
        bufferSize += n;
      }
    }

    // Prepare headers and data frames for new outgoing streams
    for (int i = 0; i < startedStreams.size(); i++) {
      final STREAM stream = startedStreams.get(i);
      bufferSize += writer.estimateInitialHeadersFrameSize(ctx, stream);
      bufferSize += prepareDataFrames(writer, stream, ctx);
    }

    // Was the remote connection window exhausted?
    final boolean remoteConnectionWindowExhausted = (remoteConnectionWindow == 0);

    // Start write if there's something to write
    final ByteBuf buf = bufferSize == 0 ? null : writer.writeStart(ctx, bufferSize);

    // Write data frames for streams that had window updates
    for (int i = 0; i < streamWindowUpdatedStreams.size(); i++) {
      final STREAM stream = streamWindowUpdatedStreams.get(i);

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
        connectionWindowBlockedStreams.add(stream);
      }
    }

    // Write data frames for streams that were blocking on a connection window update
    if (remoteConnectionWindowUpdated) {
      while (true) {
        final STREAM stream = connectionWindowBlockedStreams.peekFirst();
        if (stream == null) {
          break;
        }

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
        connectionWindowBlockedStreams.removeFirst();
      }

      remoteConnectionWindowUpdated = false;
    }

    // Write headers and data frames for new outgoing streams
    for (int i = 0; i < startedStreams.size(); i++) {
      final STREAM stream = startedStreams.get(i);
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
        connectionWindowBlockedStreams.add(stream);
      }
    }

    streamWindowUpdatedStreams.clear();
    startedStreams.clear();

    // Finish write if there was anything to write
    if (buf != null) {
      writer.writeEnd(ctx, buf);
    }
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
    stream.remoteWindow = remoteInitialStreamWindow;
    startedStreams.add(stream);
  }

  public void stop(final STREAM stream) {
    final Set<STREAM> s = Collections.singleton(stream);
    startedStreams.removeAll(s);
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

  void remoteInitialStreamWindowSizeUpdate(final int size) throws Http2Exception {
    // TODO: apply delta to stream windows
    final int delta = size - remoteInitialStreamWindow;
    remoteInitialStreamWindow = size;
  }

  void remoteStreamWindowUpdate(final STREAM stream, final int windowSizeIncrement) throws Http2Exception {
    if (windowSizeIncrement <= 0) {
      throw connectionError(PROTOCOL_ERROR, "Illegal window size increment: %d", windowSizeIncrement);
    }
    stream.remoteWindow += windowSizeIncrement;
    // TODO: handle multiple updates
    streamWindowUpdatedStreams.add(stream);
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
