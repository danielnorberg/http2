package io.norberg.h2client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.util.concurrent.GlobalEventExecutor;

import static io.norberg.h2client.Util.completableFuture;

public class Http2Server {

  private static final Logger log = LoggerFactory.getLogger(Http2Server.class);

  private final ChannelGroup channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE, true);

  private final ChannelFuture bindFuture;
  private final Channel channel;
  private final RequestHandler requestHandler;

  private final CompletableFuture<Void> closeFuture = new CompletableFuture<>();
  private final SslContext sslCtx;

  public Http2Server(final RequestHandler requestHandler) {
    this(requestHandler, 0);
  }

  public Http2Server(final RequestHandler requestHandler, final int port) {
    this(requestHandler, "", port);
  }

  public Http2Server(final RequestHandler requestHandler, final String hostname, final int port) {
    this.requestHandler = Objects.requireNonNull(requestHandler, "requestHandler");

    this.sslCtx = Util.defaultServerSslContext();
    final NioEventLoopGroup group = Util.defaultEventLoopGroup();

    final ServerBootstrap b = new ServerBootstrap()
        .option(ChannelOption.SO_BACKLOG, 1024)
        .group(group)
        .channel(NioServerSocketChannel.class)
        .childHandler(new Initializer());

    this.bindFuture = b.bind(hostname, port);
    this.channel = bindFuture.channel();

    channels.add(channel);
  }

  public CompletableFuture<Void> close() {
    return completableFuture(channels.close())
        .thenRun(() -> closeFuture.complete(null));
  }

  public CompletableFuture<Void> closeFuture() {
    return closeFuture;
  }

  public String hostname() {
    final InetSocketAddress localAddress = (InetSocketAddress) channel.localAddress();
    if (localAddress == null) {
      return null;
    }
    return localAddress.getHostName();
  }

  public int port() {
    final InetSocketAddress localAddress = (InetSocketAddress) channel.localAddress();
    if (localAddress == null) {
      return -1;
    }
    return localAddress.getPort();
  }

  public ChannelFuture bindFuture() {
    return bindFuture;
  }

  private class Initializer extends ChannelInitializer<SocketChannel> {

    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
      channels.add(ch);
      final ServerConnection connection = new ServerConnection(sslCtx, requestHandler);
      connection.initialize(ch);
    }
  }
}
