package io.norberg.h2client;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.LongAdder;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.ssl.SslContext;
import io.netty.util.AsciiString;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.DefaultThreadFactory;

import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpMethod.POST;
import static io.netty.handler.codec.http.HttpScheme.HTTPS;
import static io.norberg.h2client.Util.allOf;
import static io.norberg.h2client.Util.completableFuture;
import static java.util.concurrent.TimeUnit.SECONDS;

public class Http2Client2 implements ClientConnection2.Listener {

  private static final int DEFAULT_PORT = HTTPS.port();

  private final ConcurrentLinkedQueue<QueuedRequest> queue = new ConcurrentLinkedQueue<>();
  private final LongAdder outstanding = new LongAdder();

  private final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(
      0, new DefaultThreadFactory(Http2Client2.class, true));

  private final InetSocketAddress address;
  private final EventLoopGroup workerGroup;
  private final AsciiString authority;

  private final CompletableFuture<Void> closeFuture = new CompletableFuture<>();
  private final Listener listener;

  private volatile int remoteMaxConcurrentStreams = Integer.MAX_VALUE;

  private volatile ClientConnection2 pendingConnection;
  private volatile ClientConnection2 connection;
  private volatile boolean closed;
  private ClientConnection2.Builder connectionBuilder;

  private Http2Client2(final Builder builder) {
    Objects.requireNonNull(builder.address, "address");
    if (builder.address.getPort() == 0) {
      this.address = InetSocketAddress.createUnresolved(builder.address.getHostString(), DEFAULT_PORT);
      this.authority = new AsciiString(address.getHostString());
    } else {
      this.address = builder.address;
      this.authority = new AsciiString(address.getHostString() + ":" + address.getPort());
    }

    if (builder.maxConcurrentStreams != null && builder.maxConcurrentStreams < 0) {
      throw new IllegalArgumentException("Invalid maxConcurrentStreams: " + builder.maxConcurrentStreams);
    }

    this.workerGroup = Util.defaultEventLoopGroup();
    this.listener = Optional.ofNullable(builder.listener).orElse(new ListenerAdapter());

    this.connectionBuilder = ClientConnection2.builder()
        .listener(this)
        .sslContext(Optional.ofNullable(builder.sslContext).orElseGet(Util::defaultClientSslContext))
        .maxConcurrentStreams(builder.maxConcurrentStreams)
        .maxFrameSize(builder.maxFrameSize)
        .connectionWindowSize(builder.connectionWindow)
        .initialStreamWindowSize(builder.streamWindow);

    connect();
  }

  public CompletableFuture<Void> close() {
    closed = true;
    scheduler.shutdownNow();
    final List<CompletableFuture<?>> closeFutures = new ArrayList<>();
    final ClientConnection2 pendingConnection = this.pendingConnection;
    if (pendingConnection != null) {
      closeFutures.add(completableFuture(pendingConnection.close()));
    }
    final ClientConnection2 connection = this.connection;
    if (connection != null) {
      closeFutures.add(completableFuture(connection.close()));
    }
    allOf(closeFutures).whenComplete((ignore, ex) -> closeFuture.complete(null));
    return closeFuture;
  }

  public CompletableFuture<Void> closeFuture() {
    return closeFuture;
  }

  public CompletableFuture<Http2Response> get(final CharSequence uri) {
    final Http2Request request = new Http2Request(GET, uri);
    return send(request);
  }

  public CompletableFuture<Http2Response> post(final CharSequence uri, final ByteBuffer data) {
    return post(uri, Unpooled.wrappedBuffer(data));
  }

  public CompletableFuture<Http2Response> post(final CharSequence uri, final ByteBuf data) {
    final Http2Request request = new Http2Request(POST, uri, data);
    return send(request);
  }

  public void send(final Http2Request request, final Http2ResponseHandler responseHandler) {
    // Racy but that's fine, the real limiting happens on the connection.
    // This is just to put a bound on the request and write queues.
    final long outstanding = this.outstanding.longValue();
    if (outstanding > remoteMaxConcurrentStreams) {
      responseHandler.failure(new OutstandingRequestLimitReachedException());
      return;
    }
    this.outstanding.increment();

    final ClientConnection2 connection = this.connection;

    // Connected? Send immediately.
    if (connection != null) {
      send(connection, request, responseHandler);
      return;
    }

    queue.add(new QueuedRequest(request, responseHandler));

    // Guard against connection race
    pump();
  }

  public CompletableFuture<Http2Response> send(final Http2Request request) {
    final CompletableFuture<Http2Response> future = new CompletableFuture<>();
    send(request, new Http2ResponseHandler() {
      @Override
      public void response(final Http2Response response) {
        future.complete(response);
      }

      @Override
      public void failure(final Throwable e) {
        future.completeExceptionally(e);
      }
    });
    return future;
  }

  private void send(final ClientConnection2 connection, final Http2Request request,
                    final Http2ResponseHandler responseHandler) {
    request.authority(authority);
    request.scheme(HTTPS.name());
    connection.send(request, responseHandler);
  }

  private void connect() {
    // Do nothing if the client is closed
    if (closed) {
      return;
    }
    final Bootstrap b = new Bootstrap()
        .group(workerGroup)
        .channel(NioSocketChannel.class)
        .option(ChannelOption.SO_KEEPALIVE, true)
        .remoteAddress(address)
        .handler(new ConnectionInitializer())
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000);

    final ChannelFuture future = b.connect();

    future.addListener(f -> {
      if (!f.isSuccess()) {
        connectFailed(f.cause());
        return;
      }
    });
  }

  private void connectFailed(final Throwable cause) {
    // Fail outstanding requests
    // TODO: let requests time out instead?
    while (true) {
      final QueuedRequest request = queue.poll();
      if (request == null) {
        break;
      }
      outstanding.decrement();
      request.responseHandler.failure(cause);
    }

    // Retry
    try {
      // TODO: exponential backoff
      scheduler.schedule(this::connect, 1, SECONDS);
    } catch (RejectedExecutionException ignore) {
      // Client is closed, ignore
    }
  }

  private void pump() {
    final ClientConnection2 connection = this.connection;
    if (connection == null) {
      return;
    }
    while (!connection.isDisconnected()) {
      final QueuedRequest queuedRequest = queue.poll();
      if (queuedRequest == null) {
        break;
      }
      send(connection, queuedRequest.request, queuedRequest.responseHandler);
    }
  }

  @Override
  public void peerSettingsChanged(final ClientConnection2 connection, final Http2Settings settings) {
    if (settings.maxConcurrentStreams() != null) {
      remoteMaxConcurrentStreams = settings.maxConcurrentStreams().intValue();
    }
    listener.peerSettingsChanged(Http2Client2.this, settings);
  }

  @Override
  public void requestFailed(final ClientConnection2 connection) {
    outstanding.decrement();
  }

  @Override
  public void responseReceived(final ClientConnection2 connection, final Http2Response response) {
    outstanding.decrement();
  }

  private static class QueuedRequest {

    private final Http2Request request;
    private final Http2ResponseHandler responseHandler;

    public QueuedRequest(final Http2Request request, final Http2ResponseHandler responseHandler) {

      this.request = request;
      this.responseHandler = responseHandler;
    }
  }

  public static Http2Client2 of(final String host) {
    return builder().address(host).build();
  }

  public static Http2Client2 of(final String host, final int port) {
    return builder().address(host, port).build();
  }

  public static Http2Client2 of(final InetSocketAddress address) {
    return builder().address(address).build();
  }

  public static Http2Client2 of(final InetAddress address) {
    return builder().address(address).build();
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {

    // Max concurrent streams is unlimited by default in the http2 protocol. Set a sane default limit.
    private static final Integer DEFAULT_MAX_CONCURRENT_STREAMS = 100;

    private InetSocketAddress address;
    private Listener listener;
    private SslContext sslContext;

    private Integer maxConcurrentStreams = DEFAULT_MAX_CONCURRENT_STREAMS;
    private Integer maxFrameSize;
    private Integer connectionWindow;
    private Integer streamWindow;

    public Builder address(final String host) {
      return address(InetSocketAddress.createUnresolved(host, 0));
    }

    public Builder address(final String host, final int port) {
      return address(InetSocketAddress.createUnresolved(host, port));
    }

    public Builder address(final InetSocketAddress address) {
      this.address = address;
      return this;
    }

    public Builder address(final InetAddress address) {
      return address(new InetSocketAddress(address, 0));
    }

    public Builder listener(final Listener listener) {
      this.listener = listener;
      return this;
    }

    public Builder maxConcurrentStreams(final int maxConcurrentStreams) {
      this.maxConcurrentStreams = maxConcurrentStreams;
      return this;
    }

    public Builder maxFrameSize(final int maxFrameSize) {
      this.maxFrameSize = maxFrameSize;
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

    public Http2Client2 build() {
      return new Http2Client2(this);
    }
  }

  public interface Listener {

    /**
     * Called when remote peer settings changed.
     */
    void peerSettingsChanged(Http2Client2 client, Http2Settings settings);

    /**
     * Called when a client connection is established.
     */
    void connectionEstablished(Http2Client2 client);

    /**
     * Called when a client connection is closed.
     */
    void connectionClosed(Http2Client2 client);
  }

  public static class ListenerAdapter implements Listener {

    @Override
    public void peerSettingsChanged(final Http2Client2 client, final Http2Settings settings) {

    }

    @Override
    public void connectionEstablished(final Http2Client2 client) {

    }

    @Override
    public void connectionClosed(final Http2Client2 client) {

    }
  }

  private class ConnectionInitializer extends ChannelInboundHandlerAdapter {

    @Override
    public void channelActive(final ChannelHandlerContext ctx) throws Exception {

      final ClientConnection2 pendingConnection = connectionBuilder.build(ctx.channel());
      Http2Client2.this.pendingConnection = pendingConnection;
      ctx.channel().attr(AttributeKey.valueOf(Http2Client2.class, ClientConnection2.class.getSimpleName()))
          .set(pendingConnection);

      pendingConnection.connectFuture().whenComplete((c, ex) -> {
        // TODO: move this to listener?
        if (ex != null) {
          connectFailed(ex);
          return;
        }

        // Connection and handshake succeeded, publish the new connection
        connection = c;

        // Bail if we were closed while connecting
        if (closed) {
          c.close();
          return;
        }

        // Reconnect on disconnect
        c.disconnectFuture().whenComplete((dc, dex) -> {

          // Notify listener that the connection was closed
          listener.connectionClosed(Http2Client2.this);

          // TODO: backoff
          connect();
        });

        // Notify listener that the connection was established
        listener.connectionEstablished(Http2Client2.this);

        // Send queued requests
        pump();
      });

      ctx.pipeline().remove(this);
      ctx.fireChannelActive();
    }
  }
}
