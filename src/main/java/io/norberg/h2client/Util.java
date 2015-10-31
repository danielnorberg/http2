package io.norberg.h2client;

import java.security.cert.CertificateException;
import java.util.concurrent.CompletableFuture;

import javax.net.ssl.SSLException;

import io.netty.channel.nio.NioEventLoopGroup;
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
import io.netty.util.concurrent.Future;

class Util {

  private static class LazyDefaultEventLoopGroup {

    private static final NioEventLoopGroup INSTANCE = new NioEventLoopGroup();
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

  static CompletableFuture<Void> completableFuture(final Future<Void> f) {
    final CompletableFuture<Void> cf = new CompletableFuture<>();
    f.addListener(future -> {
      if (f.isSuccess()) {
        cf.complete(null);
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
}
