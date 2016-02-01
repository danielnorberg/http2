package io.norberg.h2client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.Spliterator;
import java.util.function.Consumer;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.util.collection.IntObjectHashMap;

import static io.netty.handler.codec.http2.Http2Error.PROTOCOL_ERROR;
import static io.norberg.h2client.Util.connectionError;
import static java.lang.Math.min;

class StreamController<CTX, STREAM extends Stream> implements Iterable<STREAM> {

  private final IntObjectHashMap<STREAM> streams = new IntObjectHashMap<>();

  private final List<STREAM> started = new ArrayList<>();
  private final Queue<STREAM> channelWindowed = new ArrayDeque<>();
  private final List<STREAM> pending = new ArrayList<>();

  private int remoteInitialStreamWindow = Http2Protocol.DEFAULT_INITIAL_WINDOW_SIZE;
  private int remoteConnectionWindow = Http2Protocol.DEFAULT_INITIAL_WINDOW_SIZE;

  private boolean remoteWindowUpdated;


  void addStream(final STREAM stream) {
    streams.put(stream.id, stream);
  }

  STREAM removeStream(final int id) {
    return streams.remove(id);
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

  public void write(final CTX ctx, final StreamWriter<CTX, STREAM> writer) throws Http2Exception {
    int bufferSize = 0;

    // Prepare data frames for all streams that had window updates
    for (int i = 0; i < pending.size(); i++) {
      final STREAM stream = pending.get(i);
      bufferSize += prepareDataFrame(writer, stream, ctx);
    }

    // Prepare data frames for all streams that were blocking on a channel window update
    if (remoteWindowUpdated) {
      for (final STREAM stream : channelWindowed) {
        final int n = prepareDataFrame(writer, stream, ctx);
        if (n == 0) {
          break;
        }
        bufferSize += n;
      }
    }

    // Prepare headers and data frames for new outgoing streams
    for (int i = 0; i < started.size(); i++) {
      final STREAM stream = started.get(i);

      bufferSize += writer.estimateInitialHeadersFrameSize(ctx, stream);
      bufferSize += prepareDataFrame(writer, stream, ctx);
    }

    final ByteBuf buf = writer.writeStart(ctx, bufferSize);

    // Write data frames for streams that had window updates
    for (int i = 0; i < pending.size(); i++) {
      final STREAM stream = pending.get(i);
      final int size = stream.fragmentSize;
      final boolean endOfStream = stream.data.readableBytes() == size;
      if (size > 0) {
        writer.writeDataFrame(ctx, buf, stream, size, endOfStream);
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
        final STREAM stream = channelWindowed.peek();
        if (stream == null) {
          break;
        }
        final int size = stream.fragmentSize;
        if (size == 0) {
          break;
        }
        final boolean endOfStream = stream.data.readableBytes() == size;
        writer.writeDataFrame(ctx, buf, stream, size, endOfStream);
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

      final STREAM stream = started.get(i);

      // Write headers
      final boolean hasContent = stream.data != null && stream.data.isReadable();
      writer.writeInitialHeadersFrame(ctx, buf, stream, !hasContent);

      // Write data
      if (hasContent) {
        final int size = stream.fragmentSize;
        final boolean endOfStream = stream.data.readableBytes() == size;
        writer.writeDataFrame(ctx, buf, stream, size, endOfStream);
        if (buf.isReadable() && stream.remoteWindow > 0) {
          channelWindowed.add(stream);
        }
      }
    }

    pending.clear();
    started.clear();

    writer.writeEnd(ctx, buf);
  }

  public void start(final STREAM stream) {
    stream.remoteWindow = remoteInitialStreamWindow;
    started.add(stream);
  }

  public void remoteConnectionWindowUpdate(final int sizeIncrement) throws Http2Exception {
    remoteConnectionWindow += sizeIncrement;
    remoteWindowUpdated = true;
  }

  public int streams() {
    return streams.size();
  }

  public STREAM stream(final int id) {
    return streams.get(id);
  }

  @Override
  public Iterator<STREAM> iterator() {
    return streams.values().iterator();
  }

  @Override
  public void forEach(final Consumer<? super STREAM> action) {
    streams.values().forEach(action);
  }

  @Override
  public Spliterator<STREAM> spliterator() {
    return streams.values().spliterator();
  }

  public void remoteInitialStreamWindowSizeUpdate(final int size) throws Http2Exception {
    // TODO: apply delta to stream windows
    final int delta = size - remoteInitialStreamWindow;
    remoteInitialStreamWindow = size;
  }

  public void remoteStreamWindowUpdate(final int streamId, final int windowSizeIncrement) throws Http2Exception {
    final STREAM stream = stream(streamId);
    if (stream == null) {
      throw connectionError(PROTOCOL_ERROR, "Unknown stream id %d", streamId);
    }
    stream.remoteWindow += windowSizeIncrement;
    // TODO: handle multiple updates
    pending.add(stream);
  }
}
