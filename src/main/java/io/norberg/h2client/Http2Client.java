package io.norberg.h2client;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.LongAdder;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.ssl.SslContext;
import io.netty.util.AsciiString;
import io.netty.util.concurrent.DefaultThreadFactory;

import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpMethod.POST;
import static io.netty.handler.codec.http.HttpScheme.HTTPS;
import static java.util.concurrent.TimeUnit.SECONDS;

public class Http2Client implements ClientConnection.Listener {

  private static final int DEFAULT_MAX_CONCURRENT_STREAMS = 100;
  private static final int DEFAULT_MAX_FRAME_SIZE = 1024 * 1024;

  private static final int DEFAULT_PORT = HTTPS.port();

  private final ConcurrentLinkedQueue<QueuedRequest> queue = new ConcurrentLinkedQueue<>();
  private final LongAdder outstanding = new LongAdder();

  private final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(
      0, new DefaultThreadFactory(Http2Client.class, true));


  private final InetSocketAddress address;
  private final EventLoopGroup workerGroup;
  private final AsciiString authority;
  private final SslContext sslContext;

  private final CompletableFuture<Void> closeFuture = new CompletableFuture<>();
  private final Listener listener;

  private volatile int maxConcurrentStreams;
  private volatile int maxFrameSize;
  private volatile ClientConnection connection;
  private volatile boolean closed;

  private Http2Client(final Builder builder) {
    InetSocketAddress address = Objects.requireNonNull(builder.address, "address");
    if (address.getPort() == 0) {
      this.address = InetSocketAddress.createUnresolved(address.getHostString(), DEFAULT_PORT);
      this.authority = new AsciiString(address.getHostString());
    } else {
      this.address = address;
      this.authority = new AsciiString(address.getHostString() + ":" + address.getPort());
    }
    if (builder.maxConcurrentStreams < 0) {
      throw new IllegalArgumentException("Invalid maxConcurrentStreams: " + builder.maxConcurrentStreams);
    }
    this.maxConcurrentStreams = builder.maxConcurrentStreams;
    this.maxFrameSize = builder.maxFrameSize;
    this.sslContext = Optional.ofNullable(builder.sslContext).orElseGet(Util::defaultClientSslContext);
    this.workerGroup = Util.defaultEventLoopGroup();
    this.listener = Optional.ofNullable(builder.listener).orElse(new ListenerAdapter());
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
    // Racy but that's fine, the real limiting happens on the connection
    final long outstanding = this.outstanding.longValue();
    if (outstanding > maxConcurrentStreams) {
      responseHandler.failure(new OutstandingRequestLimitReachedException());
      return;
    }
    this.outstanding.increment();

    final ClientConnection connection = this.connection;

    // Are we connected? Then send immediately.
    if (connection.isConnected()) {
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

  private void send(final ClientConnection connection, final Http2Request request,
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

    final ClientConnection connection = ClientConnection.builder()
        .address(address)
        .workerGroup(workerGroup)
        .sslContext(sslContext)
        .listener(this)
        .maxConcurrentStreams(maxConcurrentStreams)
        .maxFrameSize(maxFrameSize)
        .build();

    connection.connectFuture().whenComplete((c, ex) -> {
      if (ex != null) {
        // Fail outstanding requests
        while (true) {
          final QueuedRequest request = queue.poll();
          if (request == null) {
            break;
          }
          outstanding.decrement();
          request.responseHandler.failure(ex);
        }

        // Retry
        // TODO: exponential backoff
        scheduler.schedule(this::connect, 1, SECONDS);
        return;
      }

      // Reconnect on disconnect
      connection.disconnectFuture().whenComplete((dc, dex) -> {

        // Notify listener that the connection was closed
        listener.connectionClosed(Http2Client.this);

        // TODO: backoff
        connect();
      });

      // Notify listener that the connection was established
      listener.connectionEstablished(Http2Client.this);

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
      send(connection, queuedRequest.request, queuedRequest.responseHandler);
    }
  }

  @Override
  public void peerSettingsChanged(final ClientConnection connection, final Http2Settings settings) {
    if (settings.maxConcurrentStreams() != null) {
      maxConcurrentStreams = settings.maxConcurrentStreams().intValue();
    }
    listener.peerSettingsChanged(Http2Client.this, settings);
  }

  @Override
  public void requestFailed(final ClientConnection connection) {
    outstanding.decrement();
  }

  @Override
  public void responseReceived(final ClientConnection connection, final Http2Response response) {
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

  public static Http2Client of(final String host) {
    return builder().address(host).build();
  }

  public static Http2Client of(final String host, final int port) {
    return builder().address(host, port).build();
  }

  public static Http2Client of(final InetSocketAddress address) {
    return builder().address(address).build();
  }

  public static Http2Client of(final InetAddress address) {
    return builder().address(address).build();
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {

    private InetSocketAddress address;
    private Listener listener;
    private SslContext sslContext;

    private int maxConcurrentStreams = DEFAULT_MAX_CONCURRENT_STREAMS;
    private int maxFrameSize = DEFAULT_MAX_FRAME_SIZE;

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

    public Http2Client build() {
      return new Http2Client(this);
    }
  }

  public interface Listener {

    /**
     * Called when remote peer settings changed.
     */
    void peerSettingsChanged(Http2Client client, Http2Settings settings);

    /**
     * Called when a client connection is established.
     */
    void connectionEstablished(Http2Client client);

    /**
     * Called when a client connection is closed.
     */
    void connectionClosed(Http2Client client);
  }

  public static class ListenerAdapter implements Listener {

    @Override
    public void peerSettingsChanged(final Http2Client client, final Http2Settings settings) {

    }

    @Override
    public void connectionEstablished(final Http2Client client) {

    }

    @Override
    public void connectionClosed(final Http2Client client) {

    }
  }
}
