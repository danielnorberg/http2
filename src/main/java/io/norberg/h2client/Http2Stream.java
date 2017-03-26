package io.norberg.h2client;

import io.netty.buffer.ByteBuf;

/**
 * A flow controlled stream.
 */
class Http2Stream {

  final int id;

  //================================================================================
  // Outgoing (remote) flow control
  //================================================================================

  /**
   * Outgoing data buffer.
   */
  ByteBuf data;

  /**
   * The remote window size in octets.
   */
  int remoteWindow;

  /**
   * The flow control computed fragment data frame payload size.
   */
  int fragmentSize;

  /**
   * The number of data frames to write in the current flush.
   */
  int frames;

  /**
   * Is this stream already pending processing at the next flush?
   */
  boolean pending;

  /**
   * Has this stream started sending?
   */
  boolean started;

  //================================================================================
  // Incoming (local) flow control
  //================================================================================

  int localWindow;

  Http2Stream(final int id) {
    this(id, null);
  }

  Http2Stream(final int id, final ByteBuf data) {
    if (id < 1) {
      throw new IllegalArgumentException("stream id cannot be < 1");
    }
    this.id = id;
    this.data = data;
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    final Http2Stream stream = (Http2Stream) o;

    return id == stream.id;

  }

  @Override
  public int hashCode() {
    return id;
  }

  @Override
  public String toString() {
    return "Http2Stream{" +
           "id=" + id +
           ", data=" + data +
           ", remoteWindow=" + remoteWindow +
           '}';
  }
}