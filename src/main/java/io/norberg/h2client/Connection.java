package io.norberg.h2client;

import java.io.Closeable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLException;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

class Connection implements Closeable {

  private final Channel channel;

  private final AtomicInteger streamId = new AtomicInteger(3);
  private volatile ResponseHandler responseHandler;

  private final CompletableFuture<Void> connectFuture = new CompletableFuture<>();

  private volatile boolean connected = false;

  Connection(final String host, final int port, final EventLoopGroup workerGroup) {
    final SslProvider provider = OpenSsl.isAlpnSupported() ? SslProvider.OPENSSL : SslProvider.JDK;
    final SslContext sslCtx;
    try {
      sslCtx = SslContextBuilder.forClient()
          .sslProvider(provider)
          .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
          .trustManager(InsecureTrustManagerFactory.INSTANCE)
          .applicationProtocolConfig(new ApplicationProtocolConfig(
              ApplicationProtocolConfig.Protocol.ALPN,
              ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
              ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
              ApplicationProtocolNames.HTTP_2))
          .build();
    } catch (SSLException e) {
      throw new RuntimeException(e);
    }

    final Initializer initializer = new Initializer(sslCtx, Integer.MAX_VALUE);

    final Bootstrap b = new Bootstrap();
    b.group(workerGroup);
    b.channel(NioSocketChannel.class);
    b.option(ChannelOption.SO_KEEPALIVE, true);
    b.remoteAddress(host, port);
    b.handler(initializer);

    final ChannelFuture connectFuture = b.connect();

    this.channel = connectFuture.channel();

    connectFuture.addListener((f) -> {
      if (!connectFuture.isSuccess()) {
        // TODO: fail
        return;
      }

      // Wait for the HTTP/2 upgrade to occur.
      SettingsHandler settingsHandler = initializer.settingsHandler();
      settingsHandler.promise.addListener((settings) -> {
        if (!settings.isSuccess()) {
          // TODO: fail
          return;
        }
        this.responseHandler = initializer.responseHandler();
        connected = true;
        this.connectFuture.complete(null);
      });
    });
  }

  CompletableFuture<FullHttpResponse> send(final HttpRequest request) {
    final CompletableFuture<FullHttpResponse> future = new CompletableFuture<>();
    final int streamId = this.streamId.getAndAdd(2);
    responseHandler.expect(streamId, future);
    final ChannelFuture writeFuture = channel.writeAndFlush(request);
    writeFuture.addListener((f) -> {
      if (!writeFuture.isSuccess()) {
        responseHandler.cancel(streamId);
        future.completeExceptionally(writeFuture.cause());
      }
    });
    return future;
  }

  CompletableFuture<Void> connectFuture() {
    return connectFuture;
  }

  boolean isConnected() {
    return connected;
  }

  public void close() {
    channel.close();
  }
}
