package io.norberg.h2client;

import com.spotify.netty4.util.BatchFlusher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.DefaultHttp2ConnectionDecoder;
import io.netty.handler.codec.http2.DefaultHttp2ConnectionEncoder;
import io.netty.handler.codec.http2.DefaultHttp2FrameReader;
import io.netty.handler.codec.http2.DefaultHttp2FrameWriter;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Flags;
import io.netty.handler.codec.http2.Http2FrameListener;
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.Http2FrameReader;
import io.netty.handler.codec.http2.Http2FrameWriter;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2InboundFrameLogger;
import io.netty.handler.codec.http2.Http2OutboundFrameLogger;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.ssl.SslContext;
import io.netty.util.concurrent.GlobalEventExecutor;

import static io.netty.buffer.Unpooled.EMPTY_BUFFER;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static io.netty.handler.codec.http2.HttpConversionUtil.ExtensionHeaderNames.STREAM_ID;
import static io.netty.handler.logging.LogLevel.TRACE;
import static io.norberg.h2client.Util.completableFuture;
import static io.norberg.h2client.Util.failure;

public class Http2Server {

  private static final Logger log = LoggerFactory.getLogger(Http2Server.class);

  private static final int MAX_CONTENT_LENGTH = Integer.MAX_VALUE;

  private final ChannelGroup channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE, true);

  private final ChannelFuture bindFuture;
  private final Channel channel;
  private final RequestHandler requestHandler;

  private final CompletableFuture<Void> closeFuture = new CompletableFuture<>();

  public Http2Server(final RequestHandler requestHandler) {
    this(requestHandler, 0);
  }

  public Http2Server(final RequestHandler requestHandler, final int port) {
    this(requestHandler, "", port);
  }

  public Http2Server(final RequestHandler requestHandler, final String hostname, final int port) {
    this.requestHandler = Objects.requireNonNull(requestHandler, "requestHandler");

    final SslContext sslCtx = Util.defaultServerSslContext();
    final NioEventLoopGroup group = Util.defaultEventLoopGroup();

    final ServerBootstrap b = new ServerBootstrap()
        .option(ChannelOption.SO_BACKLOG, 1024)
        .group(group)
        .channel(NioServerSocketChannel.class)
        .childHandler(new Initializer(sslCtx));

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

  private class Handler extends ChannelInboundHandlerAdapter {

    private final BatchFlusher flusher;

    public Handler(Channel channel) {
      flusher = new BatchFlusher(channel);
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
      if (!(msg instanceof FullHttpRequest)) {
        if (msg instanceof Http2Settings) {
          log.debug("Got settings: {}", msg);
          return;
        }
        ctx.fireChannelRead(msg);
        return;
      }

      final FullHttpRequest request = (FullHttpRequest) msg;
      final int streamId = streamId(request);

      // Hand off request to request handler
      final CompletableFuture<FullHttpResponse> responseFuture = dispatch(request);

      // Handle response
      responseFuture

          // Return 500 for request handler errors
          .exceptionally((ex) -> {
            final FullHttpResponse response = new DefaultFullHttpResponse(
                HTTP_1_1, INTERNAL_SERVER_ERROR, EMPTY_BUFFER);
            streamId(response, streamId);
            return response;
          })

          // Send response
          .thenAccept(response -> {
            streamId(response, streamId);
            ctx.write(response);
            flusher.flush();
          });

    }

    private CompletableFuture<FullHttpResponse> dispatch(final FullHttpRequest request) {
      try {
        return requestHandler.handleRequest(request);
      } catch (Exception e) {
        return failure(e);
      }
    }

    private int streamId(FullHttpRequest request) {
      return request.headers().getInt(STREAM_ID.text());
    }

    private void streamId(FullHttpResponse response, int streamId) {
      response.headers().setInt(STREAM_ID.text(), streamId);
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
      log.error("caught exception, closing connection", cause);
      ctx.close();
    }
  }

  private class Initializer extends ChannelInitializer<SocketChannel> {

    private final Http2FrameLogger logger = new Http2FrameLogger(TRACE, Initializer.class);

    private final SslContext sslCtx;

    public Initializer(final SslContext sslCtx) {
      this.sslCtx = sslCtx;
    }

    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
      channels.add(ch);

      final Http2Connection connection = new DefaultHttp2Connection(true);

      final Http2FrameReader reader = new Http2InboundFrameLogger(new DefaultHttp2FrameReader(true), logger);
      final Http2FrameWriter writer = new Http2OutboundFrameLogger(new DefaultHttp2FrameWriter(), logger);

      final Http2ConnectionEncoder encoder = new DefaultHttp2ConnectionEncoder(connection, writer);
      final Http2ConnectionDecoder decoder = new DefaultHttp2ConnectionDecoder(connection, encoder, reader);
      decoder.frameListener(new FrameListener());

      final Http2Settings settings = new Http2Settings();

      final Http2ConnectionHandler connectionHandler = new Http2ConnectionHandler(decoder, encoder, settings) {};

      ch.pipeline().addLast(sslCtx.newHandler(ch.alloc()), connectionHandler, new Handler(ch));
    }
  }

  private class FrameListener implements Http2FrameListener {

    @Override
    public int onDataRead(final ChannelHandlerContext ctx, final int streamId, final ByteBuf data, final int padding,
                          final boolean endOfStream)
        throws Http2Exception {
      final Http2Request
      if (endOfStream) {
        requestHandler.handleRequest()
      }
    }

    @Override
    public void onHeadersRead(final ChannelHandlerContext ctx, final int streamId, final Http2Headers headers,
                              final int padding,
                              final boolean endOfStream) throws Http2Exception {

    }

    @Override
    public void onHeadersRead(final ChannelHandlerContext ctx, final int streamId, final Http2Headers headers,
                              final int streamDependency,
                              final short weight, final boolean exclusive, final int padding, final boolean endOfStream)
        throws Http2Exception {

    }

    @Override
    public void onPriorityRead(final ChannelHandlerContext ctx, final int streamId, final int streamDependency,
                               final short weight,
                               final boolean exclusive) throws Http2Exception {

    }

    @Override
    public void onRstStreamRead(final ChannelHandlerContext ctx, final int streamId, final long errorCode)
        throws Http2Exception {

    }

    @Override
    public void onSettingsAckRead(final ChannelHandlerContext ctx) throws Http2Exception {

    }

    @Override
    public void onSettingsRead(final ChannelHandlerContext ctx, final Http2Settings settings) throws Http2Exception {

    }

    @Override
    public void onPingRead(final ChannelHandlerContext ctx, final ByteBuf data) throws Http2Exception {

    }

    @Override
    public void onPingAckRead(final ChannelHandlerContext ctx, final ByteBuf data) throws Http2Exception {

    }

    @Override
    public void onPushPromiseRead(final ChannelHandlerContext ctx, final int streamId, final int promisedStreamId,
                                  final Http2Headers headers,
                                  final int padding) throws Http2Exception {

    }

    @Override
    public void onGoAwayRead(final ChannelHandlerContext ctx, final int lastStreamId, final long errorCode,
                             final ByteBuf debugData)
        throws Http2Exception {

    }

    @Override
    public void onWindowUpdateRead(final ChannelHandlerContext ctx, final int streamId, final int windowSizeIncrement)
        throws Http2Exception {

    }

    @Override
    public void onUnknownFrame(final ChannelHandlerContext ctx, final byte frameType, final int streamId,
                               final Http2Flags flags, final ByteBuf payload)
        throws Http2Exception {

    }
  }
}
