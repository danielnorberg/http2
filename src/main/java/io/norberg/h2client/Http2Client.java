package io.norberg.h2client;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

import io.netty.buffer.Unpooled;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http2.HttpConversionUtil;
import io.netty.util.AsciiString;

import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpMethod.POST;
import static io.netty.handler.codec.http.HttpScheme.HTTPS;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

public class Http2Client implements Closeable {

  private static final int DEFAULT_PORT = HTTPS.port();

  private final NioEventLoopGroup workerGroup;
  private final AsciiString hostName;

  private volatile Connection connection;

  public Http2Client(final String host) {
    this(host, DEFAULT_PORT);
  }

  public Http2Client(final String host, final int port) {
    this.workerGroup = new NioEventLoopGroup();
    this.connection = new Connection(host, port, workerGroup);
    this.hostName = new AsciiString(host + ':' + port);
  }

  @Override
  public void close() throws IOException {
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

  private CompletableFuture<FullHttpResponse> send(final HttpRequest request) {
    // TODO: queue up requests and execute them when a connection is available
    return connection.connectFuture().thenCompose((ignore) -> {
      request.headers().add(HttpHeaderNames.HOST, hostName);
      request.headers().add(HttpConversionUtil.ExtensionHeaderNames.SCHEME.text(), HTTPS.name());
      request.headers().add(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP);
      request.headers().add(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.DEFLATE);
      return connection.send(request);
    });
  }
}
