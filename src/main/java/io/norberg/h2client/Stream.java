package io.norberg.h2client;

import io.netty.buffer.ByteBuf;

class Stream {

  final int id;
  final ByteBuf data;

  int remoteWindow;

  int fragmentSize;

  Stream(final int id, final ByteBuf data) {
    if (id < 1) {
      throw new IllegalArgumentException("stream id cannot be < 1");
    }
    this.id = id;
    this.data = data;
  }

  void close() {
    if (data != null) {
      data.release();
    }
  }
}
