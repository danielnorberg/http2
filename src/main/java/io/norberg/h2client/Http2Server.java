package io.norberg.h2client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.HttpToHttp2ConnectionHandler;
import io.netty.handler.codec.http2.InboundHttp2ToHttpAdapter;
import io.netty.handler.ssl.SslContext;

import static io.netty.buffer.Unpooled.EMPTY_BUFFER;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static io.netty.handler.codec.http2.HttpConversionUtil.ExtensionHeaderNames.STREAM_ID;
import static io.netty.handler.logging.LogLevel.TRACE;

public class Http2Server {

  private static final Logger log = LoggerFactory.getLogger(Http2Server.class);

  private static final int MAX_CONTENT_LENGTH = Integer.MAX_VALUE;

  private final ChannelFuture bindFuture;
  private final Channel channel;

  public Http2Server() {
    this(0);
  }

  public Http2Server(final int port) {
    this("", port);
  }

  public Http2Server(final String hostname, final int port) {
    final SslContext sslCtx = Util.defaultServerSslContext();
    final NioEventLoopGroup group = Util.defaultEventLoopGroup();

    final ServerBootstrap b = new ServerBootstrap();
    b.option(ChannelOption.SO_BACKLOG, 1024);
    b.group(group).channel(NioServerSocketChannel.class).childHandler(new Initializer(sslCtx));

    this.bindFuture = b.bind(hostname, port);
    this.channel = bindFuture.channel();
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

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) throws Exception {
      if (!(msg instanceof FullHttpRequest)) {
        ctx.fireChannelRead(msg);
        return;
      }
      final FullHttpRequest request = (FullHttpRequest) msg;
      FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK, EMPTY_BUFFER);
      streamId(response, streamId(request));
      ctx.write(response);
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
      final DefaultHttp2Connection connection = new DefaultHttp2Connection(true);
      final InboundHttp2ToHttpAdapter listener = new InboundHttp2ToHttpAdapter.Builder(connection)
          .propagateSettings(true)
          .validateHttpHeaders(false)
          .maxContentLength(MAX_CONTENT_LENGTH)
          .build();

      ch.pipeline().addLast(sslCtx.newHandler(ch.alloc()),
                            new HttpToHttp2ConnectionHandler.Builder()
                                .frameListener(listener)
                                .frameLogger(logger)
                                .build(connection),
                            new Handler());
    }
  }
}
