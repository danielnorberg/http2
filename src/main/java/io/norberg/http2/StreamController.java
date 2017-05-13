package io.norberg.http2;

import static io.netty.handler.codec.http2.Http2Error.PROTOCOL_ERROR;
import static io.norberg.http2.Util.connectionError;

import io.netty.handler.codec.http2.Http2Exception;
import io.netty.util.collection.IntObjectHashMap;
import java.util.Iterator;
import java.util.Spliterator;
import java.util.function.Consumer;

public class StreamController<STREAM extends Http2Stream> implements Iterable<STREAM> {

  private final IntObjectHashMap<STREAM> streams = new IntObjectHashMap<>();

  STREAM addStream(final STREAM stream) {
    streams.put(stream.id, stream);
    return stream;
  }

  STREAM removeStream(final int id) {
    return streams.remove(id);
  }

  int streams() {
    return streams.size();
  }

  STREAM stream(final int id) {
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

  @Deprecated
  STREAM existingStream(final int streamId) throws Http2Exception {
    final STREAM stream = stream(streamId);
    if (stream == null) {
      throw connectionError(PROTOCOL_ERROR, "Unknown stream id: %d", streamId);
    }
    return stream;
  }
}
