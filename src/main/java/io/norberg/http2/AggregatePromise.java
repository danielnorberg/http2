package io.norberg.http2;

import java.util.List;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;

final class AggregatePromise extends DefaultChannelPromise {

  private final ChannelPromise[] promises;

  AggregatePromise(final Channel channel, final List<? extends ChannelPromise> promises) {
    super(channel);
    this.promises = promises.toArray(new ChannelPromise[promises.size()]);
  }

  @Override
  public ChannelPromise setSuccess(final Void result) {
    super.setSuccess(result);
    for (final ChannelPromise promise : promises) {
      promise.setSuccess(result);
    }
    return this;
  }

  @Override
  public boolean trySuccess() {
    final boolean result = super.trySuccess();
    for (final ChannelPromise promise : promises) {
      promise.trySuccess();
    }
    return result;
  }

  @Override
  public ChannelPromise setFailure(final Throwable cause) {
    super.setFailure(cause);
    for (final ChannelPromise promise : promises) {
      promise.setFailure(cause);
    }
    return this;
  }
}