package io.norberg.h2client;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;

import javax.net.ssl.SSLException;

import io.netty.buffer.Unpooled;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.codec.http2.HttpConversionUtil;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.util.AsciiString;

import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpMethod.POST;
import static io.netty.handler.codec.http.HttpScheme.HTTPS;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

public class Http2Client implements Closeable {

  private static final int DEFAULT_PORT = HTTPS.port();

  private final ConcurrentLinkedQueue<QueuedRequest> queue = new ConcurrentLinkedQueue<>();
  private final LongAdder outstanding = new LongAdder();

  // TODO: make configurable
  private final int maxOutstanding = 100;

  private final NioEventLoopGroup workerGroup;
  private final AsciiString hostName;
  private final SslContext sslCtx;
  private final String host;
  private final int port;

  private volatile Connection connection;
  private volatile boolean closed;

  public Http2Client(final String host) {
    this(host, DEFAULT_PORT);
  }

  public Http2Client(final String host, final int port) {
    this.host = host;
    this.port = port;

    // Set up SSL
    final SslProvider provider = OpenSsl.isAlpnSupported() ? SslProvider.OPENSSL : SslProvider.JDK;
    try {
      this.sslCtx = SslContextBuilder.forClient()
          .sslProvider(provider)
          .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
          .applicationProtocolConfig(new ApplicationProtocolConfig(
              ApplicationProtocolConfig.Protocol.ALPN,
              ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
              ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
              ApplicationProtocolNames.HTTP_2))
          .build();
    } catch (SSLException e) {
      throw new RuntimeException(e);
    }

    this.workerGroup = new NioEventLoopGroup();
    this.hostName = new AsciiString(host + ':' + port);
    connect();
  }

  @Override
  public void close() {
    closed = true;
    connection.close();
  }

  public CompletableFuture<FullHttpResponse> get(final String uri) {
    final FullHttpRequest request = new DefaultFullHttpRequest(
        HTTP_1_1, GET, uri);
    return send(request);
  }

  public CompletableFuture<FullHttpResponse> post(final String uri, final ByteBuffer data) {
    final FullHttpRequest request = new DefaultFullHttpRequest(
        HTTP_1_1, POST, uri, Unpooled.wrappedBuffer(data));
    return send(request);
  }

  public CompletableFuture<FullHttpResponse> send(final HttpRequest request) {

    final CompletableFuture<FullHttpResponse> future = new CompletableFuture<>();

    // Racy but that's fine, the real limiting happens on the connection
    final long outstanding = this.outstanding.longValue();
    if (outstanding > maxOutstanding) {
      future.completeExceptionally(new OutstandingRequestLimitReachedException());
      return future;
    }
    this.outstanding.increment();

    // Decrement outstanding when the request completes
    future.whenComplete((r, ex) -> this.outstanding.decrement());

    final Connection connection = this.connection;

    // Are we connected? Then send immediately.
    if (connection.isConnected()) {
      send(connection, request, future);
      return future;
    }

    queue.add(new QueuedRequest(request, future));

    return future;
  }

  private void send(final Connection connection, final HttpRequest request,
                    final CompletableFuture<FullHttpResponse> future) {
    request.headers().add(HttpHeaderNames.HOST, hostName);
    request.headers().add(HttpConversionUtil.ExtensionHeaderNames.SCHEME.text(), HTTPS.name());
    request.headers().add(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP);
    request.headers().add(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.DEFLATE);
    connection.send(request, future);
  }

  private void connect() {
    // Do nothing if the client is closed
    if (closed) {
      return;
    }

    final Connection connection = new Connection(host, port, workerGroup, sslCtx);
    connection.connectFuture().whenComplete((c, ex) -> {
      if (ex != null) {
        // Retry
        // TODO: backoff
        connect();
        return;
      }

      // Reconnect on disconnect
      connection.disconnectFuture().whenComplete((dc, dex) -> {
        // TODO: backoff
        connect();
      });

      // Send queued requests
      pump();
    });

    this.connection = connection;
  }

  private void pump() {
    final Connection connection = this.connection;
    while (true) {
      final QueuedRequest queuedRequest = queue.poll();
      if (queuedRequest == null) {
        break;
      }
      send(connection, queuedRequest.request, queuedRequest.future);
    }
  }

  private static class QueuedRequest {

    private final HttpRequest request;
    private final CompletableFuture<FullHttpResponse> future;

    public QueuedRequest(final HttpRequest request, final CompletableFuture<FullHttpResponse> future) {

      this.request = request;
      this.future = future;
    }
  }
}
