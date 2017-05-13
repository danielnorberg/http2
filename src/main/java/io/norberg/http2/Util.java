package io.norberg.http2;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.Future;
import java.security.cert.CertificateException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.net.ssl.SSLException;

class Util {

  static final CompletableFuture<?>[] COMPLETABLE_FUTURES = new CompletableFuture<?>[0];

  private static class LazyDefaultEventLoopGroup {

    private static final NioEventLoopGroup INSTANCE = new NioEventLoopGroup(
        0, new DefaultThreadFactory(NioEventLoopGroup.class, true));
  }

  static SslContext defaultClientSslContext() {
    final SslProvider provider = OpenSsl.isAlpnSupported() ? SslProvider.OPENSSL : SslProvider.JDK;
    try {
      return SslContextBuilder.forClient()
          .sslProvider(provider)
          .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
          // TODO: configurable trust management
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
  }

  static SslContext defaultServerSslContext() {
    try {
      final SelfSignedCertificate ssc = new SelfSignedCertificate();
      final SslProvider provider = OpenSsl.isAlpnSupported() ? SslProvider.OPENSSL : SslProvider.JDK;
      return SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey(), null)
          .sslProvider(provider)
          .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
          .applicationProtocolConfig(new ApplicationProtocolConfig(
              ApplicationProtocolConfig.Protocol.ALPN,
              ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
              ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
              ApplicationProtocolNames.HTTP_2))
          .build();
    } catch (SSLException | CertificateException e) {
      throw new RuntimeException(e);
    }
  }

  static NioEventLoopGroup defaultEventLoopGroup() {
    return LazyDefaultEventLoopGroup.INSTANCE;
  }

  static <T> CompletableFuture<T> completableFuture(final Future<T> f) {
    final CompletableFuture<T> cf = new CompletableFuture<>();
    f.addListener(future -> {
      if (f.isSuccess()) {
        cf.complete(f.getNow());
      } else {
        cf.completeExceptionally(f.cause());
      }
    });
    return cf;
  }

  static CompletableFuture<Channel> completableFuture(final ChannelFuture f) {
    final CompletableFuture<Channel> cf = new CompletableFuture<>();
    f.addListener(future -> {
      if (f.isSuccess()) {
        cf.complete(f.channel());
      } else {
        cf.completeExceptionally(f.cause());
      }
    });
    return cf;
  }

  static <T> CompletableFuture<T> failure(final Exception e) {
    final CompletableFuture<T> failure = new CompletableFuture<>();
    failure.completeExceptionally(e);
    return failure;
  }

  static CompletableFuture<Void> allOf(List<CompletableFuture<?>> futures) {
    return CompletableFuture.allOf(futures.toArray(COMPLETABLE_FUTURES));
  }

  static <T> void chain(CompletableFuture<T> in, CompletableFuture<T> out) {
    in.whenComplete((value, ex) -> {
      if (ex != null) {
        out.completeExceptionally(ex);
      } else {
        out.complete(value);
      }
    });
  }

  static Http2Exception connectionError(Http2Error error, String fmt, Object... args) {
    return new Http2Exception(error, String.format(fmt, args));
  }
}
