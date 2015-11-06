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
import io.netty.handler.ssl.SslContext;
import io.netty.util.AsciiString;
import io.netty.util.concurrent.DefaultThreadFactory;

import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpMethod.POST;
import static io.netty.handler.codec.http.HttpScheme.HTTPS;
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
  private final AsciiString authority;
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
    this.authority = new AsciiString(host + ':' + port);
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

  public CompletableFuture<Void> closeFuture() {
    return closeFuture;
  }

  public CompletableFuture<Http2Response> get(final String uri) {
    final Http2Request request = new Http2Request(GET, uri);
    return send(request);
  }

  public CompletableFuture<Http2Response> post(final String uri, final ByteBuffer data) {
    return post(uri, Unpooled.wrappedBuffer(data));
  }

  public CompletableFuture<Http2Response> post(final String uri, final ByteBuf data) {
    final Http2Request request = new Http2Request(POST, uri, data);
    return send(request);
  }

  public CompletableFuture<Http2Response> send(final Http2Request request) {

    final CompletableFuture<Http2Response> future = new CompletableFuture<>();

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

    // Guard against connection race
    pump();

    return future;
  }

  private void send(final ClientConnection connection, final Http2Request request,
                    final CompletableFuture<Http2Response> future) {
    request.headers().authority(authority);
    request.headers().scheme(HTTPS.name());
//    request.headers().add(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP);
//    request.headers().add(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.DEFLATE);
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

    private final Http2Request request;
    private final CompletableFuture<Http2Response> future;

    public QueuedRequest(final Http2Request request, final CompletableFuture<Http2Response> future) {

      this.request = request;
      this.future = future;
    }
  }
}
