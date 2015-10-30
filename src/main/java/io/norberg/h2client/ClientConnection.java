package io.norberg.h2client;

import com.spotify.netty4.util.BatchFlusher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.DelegatingDecompressorFrameListener;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.HttpToHttp2ConnectionHandler;
import io.netty.handler.codec.http2.InboundHttp2ToHttpAdapter;
import io.netty.handler.ssl.SslContext;

import static io.netty.handler.codec.http2.HttpConversionUtil.ExtensionHeaderNames.STREAM_ID;
import static io.netty.handler.logging.LogLevel.TRACE;

class ClientConnection implements Closeable {

  private static final Logger log = LoggerFactory.getLogger(ClientConnection.class);

  private static final long DEFAULT_MAX_CONCURRENT_STREAMS = 100;
  private static final int DEFAULT_MAX_FRAME_SIZE = 1024 * 1024;

  private final CompletableFuture<ClientConnection> connectFuture = new CompletableFuture<>();
  private final CompletableFuture<ClientConnection> disconnectFuture = new CompletableFuture<>();

  private final Channel channel;
  private final BatchFlusher flusher;

  private int maxFrameSize;
  private long maxConcurrentStreams;

  private volatile boolean connected;

  ClientConnection(final String host, final int port, final EventLoopGroup workerGroup, final SslContext sslCtx) {
    final Initializer initializer = new Initializer(sslCtx, Integer.MAX_VALUE);
    final Bootstrap b = new Bootstrap();
    b.group(workerGroup);
    b.channel(NioSocketChannel.class);
    b.option(ChannelOption.SO_KEEPALIVE, true);
    b.remoteAddress(host, port);
    b.handler(initializer);
    final ChannelFuture connectFuture = b.connect();
    this.channel = connectFuture.channel();
    this.flusher = new BatchFlusher(channel);
  }

  void send(final HttpRequest request, final CompletableFuture<FullHttpResponse> future) {
    assert connected;
    final ChannelPromise promise = new RequestPromise(channel, future);
    channel.write(request, promise);
    flusher.flush();
  }

  CompletableFuture<ClientConnection> connectFuture() {
    return connectFuture;
  }

  CompletableFuture<ClientConnection> disconnectFuture() {
    return disconnectFuture;
  }

  boolean isConnected() {
    return connected && channel.isActive();
  }

  public void close() {
    channel.close();
  }

  private class Initializer extends ChannelInitializer<SocketChannel> {

    private final Http2FrameLogger logger = new Http2FrameLogger(TRACE, Initializer.class);

    private final SslContext sslCtx;
    private final int maxContentLength;
    private HttpToHttp2ConnectionHandler connectionHandler;

    public Initializer(SslContext sslCtx, int maxContentLength) {
      this.sslCtx = Objects.requireNonNull(sslCtx, "sslCtx");
      this.maxContentLength = maxContentLength;
    }

    @Override
    public void initChannel(SocketChannel ch) throws Exception {
      final Http2Connection connection = new DefaultHttp2Connection(false);
      connectionHandler = new HttpToHttp2ConnectionHandler.Builder()
          .frameListener(new DelegatingDecompressorFrameListener(
              connection,
              new InboundHttp2ToHttpAdapter.Builder(connection)
                  .maxContentLength(maxContentLength)
                  .propagateSettings(true)
                  .build()))
          .frameLogger(logger)
          .build(connection);
      final SettingsHandler settingsHandler = new SettingsHandler();
      ChannelPipeline pipeline = ch.pipeline();
      final Handler handler = new Handler();
      pipeline.addLast(sslCtx.newHandler(ch.alloc()), connectionHandler, settingsHandler, handler);
    }
  }

  private class Handler extends ChannelDuplexHandler {

    private final Map<Integer, CompletableFuture<FullHttpResponse>> outstanding = new HashMap<>();

    private int streamId = 1;

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
      final Exception exception = new ConnectionClosedException();
      connectFuture.completeExceptionally(exception);
      outstanding.forEach((streamId, future) -> future.completeExceptionally(exception));
      disconnectFuture.complete(ClientConnection.this);
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
      if (!(msg instanceof FullHttpResponse)) {
        ctx.fireChannelRead(msg);
        return;
      }

      final FullHttpResponse response = (FullHttpResponse) msg;
      final int streamId = response.headers().getInt(STREAM_ID.text(), -1);
      if (streamId == -1) {
        log.warn("Received unexpected message: {}", msg);
        return;
      }

      final CompletableFuture<FullHttpResponse> future = outstanding.remove(streamId);
      if (future == null) {
        log.warn("Received unexpected message with stream id: {}", streamId);
        return;
      }

      future.complete(response);
    }

    @Override
    public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise)
        throws Exception {
      final HttpRequest request = (HttpRequest) msg;
      final RequestPromise requestPromise = (RequestPromise) promise;

      // Already at max concurrent streams? Fail fast.
      if (outstanding.size() >= maxConcurrentStreams) {
        requestPromise.responseFuture.completeExceptionally(new MaxConcurrentStreamsLimitReachedException());
        return;
      }

      // Generate ID and store response future in outstanding map to correlate with replies
      final int streamId = nextStreamId();
      request.headers().set(STREAM_ID.text(), streamId);
      outstanding.put(streamId, requestPromise.responseFuture);

      super.write(ctx, msg, promise);
    }

    private int nextStreamId() {
      streamId += 2;
      return streamId;
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
      log.error("caught exception, closing connection", cause);
      ctx.close();
    }
  }

  private class SettingsHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
      if (!(msg instanceof Http2Settings)) {
        ctx.fireChannelRead(msg);
        return;
      }

      final Http2Settings settings = (Http2Settings) msg;

      // Only care about the first settings message
      ctx.pipeline().remove(this);

      maxConcurrentStreams = Optional.ofNullable(settings.maxConcurrentStreams())
          .orElse(DEFAULT_MAX_CONCURRENT_STREAMS);
      maxFrameSize = Optional.ofNullable(settings.maxFrameSize())
          .orElse(DEFAULT_MAX_FRAME_SIZE);

      // Publish the above settings
      connected = true;
      connectFuture.complete(ClientConnection.this);
    }
  }

  private class RequestPromise extends DefaultChannelPromise {

    private final CompletableFuture<FullHttpResponse> responseFuture;

    public RequestPromise(final Channel channel, final CompletableFuture<FullHttpResponse> responseFuture) {
      super(channel);
      this.responseFuture = responseFuture;
      addListener(f -> {
        if (!isSuccess()) {
          responseFuture.completeExceptionally(cause());
        }
      });
    }
  }
}
