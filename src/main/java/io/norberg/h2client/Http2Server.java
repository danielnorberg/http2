package io.norberg.h2client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.GlobalEventExecutor;

import static io.norberg.h2client.Util.completableFuture;

public class Http2Server {

  private static final Logger log = LoggerFactory.getLogger(Http2Server.class);

  private final ChannelGroup channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE, true);

  private final CompletableFuture<Void> closeFuture = new CompletableFuture<>();
  private final EventLoopGroup group;

  private final ServerConnection.Builder connectionBuilder;

  private Http2Server(final Builder builder) {
    this.group = Util.defaultEventLoopGroup();
    this.connectionBuilder = ServerConnection.builder()
        .requestHandler(Objects.requireNonNull(builder.requestHandler, "requestHandler"))
        .sslContext(Util.defaultServerSslContext())
        .maxConcurrentStreams(builder.maxConcurrentStreams)
        .connectionWindowSize(builder.connectionWindow)
        .initialStreamWindowSize(builder.streamWindow);
  }

  public CompletableFuture<InetSocketAddress> bind(final int port) {
    return bind(new InetSocketAddress(port));
  }

  public CompletableFuture<InetSocketAddress> bind(final InetSocketAddress address) {
    final ServerBootstrap b = new ServerBootstrap()
        .option(ChannelOption.SO_BACKLOG, 1024)
        .group(group)
        .channel(NioServerSocketChannel.class)
        .childHandler(new Initializer());
    final ChannelFuture bindFuture = b.bind(address);
    final Channel channel = bindFuture.channel();
    channels.add(channel);
    return completableFuture(bindFuture).thenApply(
        ch -> (InetSocketAddress) ch.localAddress());
  }

  public CompletableFuture<Void> close() {
    return completableFuture(channels.close())
        .thenRun(() -> closeFuture.complete(null));
  }

  public CompletableFuture<Void> closeFuture() {
    return closeFuture;
  }

  public static Http2Server create(final RequestHandler requestHandler) {
    return builder()
        .requestHandler(requestHandler)
        .build();
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {

    private Integer maxConcurrentStreams;
    private List<InetSocketAddress> bind;
    private RequestHandler requestHandler;
    private Integer connectionWindow;
    private Integer streamWindow;

    private Builder() {
    }

    public Builder maxConcurrentStreams(final Integer maxConcurrentStreams) {
      this.maxConcurrentStreams = maxConcurrentStreams;
      return this;
    }

    public Builder bind() {
      return bind(0);
    }

    public Builder bind(final int port) {
      return bind(new InetSocketAddress(port));
    }

    public Builder bind(final InetSocketAddress address) {
      this.bind.add(address);
      return this;
    }

    public Builder requestHandler(final RequestHandler requestHandler) {
      this.requestHandler = requestHandler;
      return this;
    }

    public Builder connectionWindow(final Integer connectionWindow) {
      this.connectionWindow = connectionWindow;
      return this;
    }

    public Builder streamWindow(final Integer streamWindow) {
      this.streamWindow = streamWindow;
      return this;
    }

    public Http2Server build() {
      return new Http2Server(this);
    }
  }

  private class Initializer extends ChannelInitializer<SocketChannel> {

    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
      channels.add(ch);
      final ServerConnection connection = connectionBuilder.build();
      connection.initialize(ch);
    }
  }
}
