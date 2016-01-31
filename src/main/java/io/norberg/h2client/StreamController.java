package io.norberg.h2client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import io.netty.buffer.ByteBuf;
import io.netty.util.collection.IntObjectHashMap;

import static java.lang.Math.min;

class StreamController<T extends Stream> {

  private final IntObjectHashMap<T> streams = new IntObjectHashMap<>();

  private final List<Stream> started = new ArrayList<>();
  private final Queue<Stream> channelWindowed = new ArrayDeque<>();
  private final List<Stream> pending = new ArrayList<>();

  private int initialRemoteStreamWindow = Http2Protocol.DEFAULT_INITIAL_WINDOW_SIZE;
  private int remoteConnectionWindow = Http2Protocol.DEFAULT_INITIAL_WINDOW_SIZE;

  private boolean remoteWindowUpdated;


  void addStream(final T stream) {
    streams.put(stream.id, stream);
  }

  T removeStream(final int id) {
    return streams.remove(id);
  }

  int remoteConnectionWindow() {
    return remoteConnectionWindow;
  }

  public int initialRemoteStreamWindow() {
    return initialRemoteStreamWindow;
  }

  private int prepareDataFrame(final StreamWriter streamWriter, final Stream stream) {
    final ByteBuf data = stream.data;
    if (data == null) {
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
    return streamWriter.estimateDataFrameSize(stream, fragmentSize);
  }

  public void write(final StreamWriter writer) {
    int bufferSize = 0;

    // Prepare data frames for all streams that had window updates
    for (int i = 0; i < pending.size(); i++) {
      final Stream stream = pending.get(i);
      bufferSize += prepareDataFrame(writer, stream);
    }

    // Prepare data frames for all streams that were blocking on a channel window update
    if (remoteWindowUpdated) {
      for (final Stream stream : channelWindowed) {
        final int n = prepareDataFrame(writer, stream);
        if (n == 0) {
          break;
        }
        bufferSize += n;
      }
    }

    // Prepare headers and data frames for new outgoing streams
    for (int i = 0; i < started.size(); i++) {
      final Stream stream = started.get(i);

      bufferSize += writer.estimateInitialHeadersFrameSize(stream);
      bufferSize += prepareDataFrame(writer, stream);
    }

    final ByteBuf buf = writer.writeStart(bufferSize);

    // Write data frames for streams that had window updates
    for (int i = 0; i < pending.size(); i++) {
      final Stream stream = pending.get(i);
      final int size = stream.fragmentSize;
      final boolean endOfStream = stream.data.readableBytes() == size;
      if (size > 0) {
        writer.writeDataFrame(buf, stream, size, endOfStream);
      }
      if (remoteConnectionWindow == 0 &&
          stream.data.readableBytes() > 0 &&
          stream.remoteWindow > 0) {
        channelWindowed.add(stream);
      }
    }

    // Write data frames for streams that were blocking on a channel window update
    if (remoteWindowUpdated) {
      while (true) {
        final Stream stream = channelWindowed.peek();
        if (stream == null) {
          break;
        }
        final int size = stream.fragmentSize;
        if (size == 0) {
          break;
        }
        final boolean endOfStream = stream.data.readableBytes() == size;
        writer.writeDataFrame(buf, stream, size, endOfStream);
        if (remoteConnectionWindow == 0 &&
            stream.data.readableBytes() > 0 &&
            stream.remoteWindow > 0) {
          break;
        }
        channelWindowed.remove();
      }

      // Clear window update flag
      remoteWindowUpdated = false;
    }

    // Write headers and data frames for new outgoing streams
    for (int i = 0; i < started.size(); i++) {

      final Stream stream = started.get(i);

      // Write headers
      final boolean hasContent = stream.data.isReadable();
      writer.writeInitialHeadersFrame(buf, stream, !hasContent);

      // Write data
      if (hasContent) {
        final int size = stream.fragmentSize;
        final boolean endOfStream = stream.data.readableBytes() == size;
        writer.writeDataFrame(buf, stream, size, endOfStream);
        if (buf.isReadable() && stream.remoteWindow > 0) {
          channelWindowed.add(stream);
        }
      }
    }

    started.clear();

    writer.writeEnd(buf);
  }

  public void start(final T stream) {
    stream.remoteWindow = initialRemoteStreamWindow;
    started.add(stream);
  }

  public void remoteConnectionWindowUpdate(final int sizeIncrement) {
    remoteConnectionWindow += sizeIncrement;
    remoteWindowUpdated = true;
  }
}
