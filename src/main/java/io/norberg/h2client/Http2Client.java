package io.norberg.h2client;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.LongAdder;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http2.HttpConversionUtil;
import io.netty.handler.ssl.SslContext;
import io.netty.util.AsciiString;
import io.netty.util.concurrent.DefaultThreadFactory;

import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpMethod.POST;
import static io.netty.handler.codec.http.HttpScheme.HTTPS;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static java.util.concurrent.TimeUnit.SECONDS;

public class Http2Client {

  private static final int DEFAULT_PORT = HTTPS.port();

  private final ConcurrentLinkedQueue<QueuedRequest> queue = new ConcurrentLinkedQueue<>();
  private final LongAdder outstanding = new LongAdder();

  private final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(
      0, new DefaultThreadFactory(Http2Client.class, true));

  // TODO: make configurable
  private final int maxOutstanding = 100;

  private final EventLoopGroup workerGroup;
  private final AsciiString hostName;
  private final SslContext sslCtx;
  private final String host;
  private final int port;

  private final CompletableFuture<Void> closeFuture = new CompletableFuture<>();

  private volatile ClientConnection connection;
  private volatile boolean closed;

  public Http2Client(final String host) {
    this(host, DEFAULT_PORT);
  }

  public Http2Client(final String host, final int port) {
    this.host = host;
    this.port = port;
    this.sslCtx = Util.defaultClientSslContext();
    this.workerGroup = Util.defaultEventLoopGroup();
    this.hostName = new AsciiString(host + ':' + port);
    connect();
  }

  public CompletableFuture<Void> close() {
    closed = true;
    scheduler.shutdownNow();
    if (connection != null) {
      connection.close().addListener(future -> closeFuture.complete(null));
    } else {
      closeFuture.complete(null);
    }
    return closeFuture;
  }

  public CompletableFuture<FullHttpResponse> get(final String uri) {
    final FullHttpRequest request = new DefaultFullHttpRequest(
        HTTP_1_1, GET, uri);
    return send(request);
  }

  public CompletableFuture<FullHttpResponse> post(final String uri, final ByteBuffer data) {
    return post(uri, Unpooled.wrappedBuffer(data));
  }

  public CompletableFuture<FullHttpResponse> post(final String uri, final ByteBuf data) {
    final FullHttpRequest request = new DefaultFullHttpRequest(HTTP_1_1, POST, uri, data);
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

    final ClientConnection connection = this.connection;

    // Are we connected? Then send immediately.
    if (connection.isConnected()) {
      send(connection, request, future);
      return future;
    }

    queue.add(new QueuedRequest(request, future));

    return future;
  }

  private void send(final ClientConnection connection, final HttpRequest request,
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

    final ClientConnection connection = new ClientConnection(host, port, workerGroup, sslCtx);
    connection.connectFuture().whenComplete((c, ex) -> {
      if (ex != null) {
        // Fail outstanding requests
        while (true) {
          final QueuedRequest request = queue.poll();
          if (request == null) {
            break;
          }
          request.future.completeExceptionally(ex);
        }

        // Retry
        // TODO: exponential backoff
        scheduler.schedule(this::connect, 1, SECONDS);
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
    final ClientConnection connection = this.connection;
    while (connection.isConnected()) {
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
