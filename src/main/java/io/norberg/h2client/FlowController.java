package io.norberg.h2client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http2.Http2Exception;

import static io.netty.handler.codec.http2.Http2Error.PROTOCOL_ERROR;
import static io.norberg.h2client.Http2Protocol.DEFAULT_INITIAL_WINDOW_SIZE;
import static io.norberg.h2client.Util.connectionError;
import static java.lang.Math.min;

class FlowController<CTX, STREAM extends Stream> {

  private final List<STREAM> startedStreams = new ArrayList<>();
  private final Deque<STREAM> connectionWindowBlockedStreams = new ArrayDeque<>();
  private final List<STREAM> streamWindowUpdatedStreams = new ArrayList<>();

  private int remoteInitialStreamWindow;
  private int remoteConnectionWindow;

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

  public int remoteInitialStreamWindow() {
    return remoteInitialStreamWindow;
  }

  private int prepareDataFrame(final StreamWriter<CTX, STREAM> streamWriter, final STREAM stream, final CTX ctx)
      throws Http2Exception {
    final ByteBuf data = stream.data;
    if (data == null) {
      return 0;
    }
    final int dataSize = data.readableBytes();
    final int window = min(remoteConnectionWindow, stream.remoteWindow);
    // TODO: max frame size
    if (window == 0) {
      stream.fragmentSize = 0;
      return 0;
    }
    final int fragmentSize = min(dataSize, window);
    stream.fragmentSize = fragmentSize;
    stream.remoteWindow -= fragmentSize;
    remoteConnectionWindow -= fragmentSize;
    final int dataFrameSize = streamWriter.estimateDataFrameSize(ctx, stream, fragmentSize);
    return dataFrameSize;
  }

  public void flush(final CTX ctx, final StreamWriter<CTX, STREAM> writer) throws Http2Exception {
    int bufferSize = 0;

    // Prepare data frames for all streams that had window updates
    for (int i = 0; i < streamWindowUpdatedStreams.size(); i++) {
      final STREAM stream = streamWindowUpdatedStreams.get(i);
      bufferSize += prepareDataFrame(writer, stream, ctx);
    }

    // Prepare data frames for all streams that were blocking on a connection window update
    if (remoteConnectionWindowUpdated) {
      for (final STREAM stream : connectionWindowBlockedStreams) {
        final int n = prepareDataFrame(writer, stream, ctx);
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
      bufferSize += prepareDataFrame(writer, stream, ctx);
    }

    // Was the remote connection window exhausted?
    final boolean remoteConnectionWindowExhausted = (remoteConnectionWindow == 0);

    // Start write if there's something to write
    final ByteBuf buf = bufferSize == 0 ? null : writer.writeStart(ctx, bufferSize);

    // Write data frames for streams that had window updates
    for (int i = 0; i < streamWindowUpdatedStreams.size(); i++) {
      final STREAM stream = streamWindowUpdatedStreams.get(i);
      final int size = stream.fragmentSize;
      final boolean endOfStream = (stream.data.readableBytes() == size);
      if (size > 0) {
        writer.writeDataFrame(ctx, buf, stream, size, endOfStream);
        if (endOfStream) {
          writer.streamEnd(stream);
        }
      }
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
        final int size = stream.fragmentSize;
        if (size == 0) {
          break;
        }
        final boolean endOfStream = (stream.data.readableBytes() == size);
        writer.writeDataFrame(ctx, buf, stream, size, endOfStream);
        if (endOfStream) {
          writer.streamEnd(stream);
        }
        if (remoteConnectionWindowExhausted &&
            stream.data.readableBytes() > 0 &&
            stream.remoteWindow > 0) {
          break;
        }
        connectionWindowBlockedStreams.removeFirst();
      }

      remoteConnectionWindowUpdated = false;
    }

    // Write headers and data frames for new outgoing streams
    for (int i = 0; i < startedStreams.size(); i++) {
      final STREAM stream = startedStreams.get(i);

      // Write headers
      final boolean hasContent = stream.data != null && stream.data.isReadable();
      writer.writeInitialHeadersFrame(ctx, buf, stream, !hasContent);

      // Write data
      if (hasContent) {
        final int size = stream.fragmentSize;
        if (size > 0) {
          final boolean endOfStream = (stream.data.readableBytes() == size);
          writer.writeDataFrame(ctx, buf, stream, size, endOfStream);
          if (endOfStream) {
            writer.streamEnd(stream);
          }
        }
        if (remoteConnectionWindowExhausted &&
            stream.data.readableBytes() > 0 &&
            stream.remoteWindow > 0) {
          connectionWindowBlockedStreams.add(stream);
        }
      }
    }

    streamWindowUpdatedStreams.clear();
    startedStreams.clear();

    // Finish write if there was anything to write
    if (buf != null) {
      writer.writeEnd(ctx, buf);
    }
  }

  public void start(final STREAM stream) {
    stream.remoteWindow = remoteInitialStreamWindow;
    startedStreams.add(stream);
  }

  public void remoteConnectionWindowUpdate(final int sizeIncrement) throws Http2Exception {
    if (sizeIncrement <= 0) {
      throw connectionError(PROTOCOL_ERROR, "Illegal window size increment: %d", sizeIncrement);
    }
    remoteConnectionWindow += sizeIncrement;
    remoteConnectionWindowUpdated = true;
  }

  public void remoteInitialStreamWindowSizeUpdate(final int size) throws Http2Exception {
    // TODO: apply delta to stream windows
    final int delta = size - remoteInitialStreamWindow;
    remoteInitialStreamWindow = size;
  }

  public void remoteStreamWindowUpdate(final STREAM stream, final int windowSizeIncrement) throws Http2Exception {
    if (windowSizeIncrement <= 0) {
      throw connectionError(PROTOCOL_ERROR, "Illegal window size increment: %d", windowSizeIncrement);

    }
    stream.remoteWindow += windowSizeIncrement;
    // TODO: handle multiple updates
    streamWindowUpdatedStreams.add(stream);
  }
}
