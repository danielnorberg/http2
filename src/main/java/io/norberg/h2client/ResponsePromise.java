package io.norberg.h2client;

import io.netty.channel.Channel;
import io.netty.channel.DefaultChannelPromise;

public class ResponsePromise extends DefaultChannelPromise {

  final int streamId;

  public ResponsePromise(final Channel channel, final int streamId) {
    super(channel);
    this.streamId = streamId;
  }
}
