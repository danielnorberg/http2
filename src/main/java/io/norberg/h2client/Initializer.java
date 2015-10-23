package io.norberg.h2client;

import java.util.Objects;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.DelegatingDecompressorFrameListener;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.HttpToHttp2ConnectionHandler;
import io.netty.handler.codec.http2.InboundHttp2ToHttpAdapter;
import io.netty.handler.ssl.SslContext;

import static io.netty.handler.logging.LogLevel.INFO;

class Initializer extends ChannelInitializer<SocketChannel> {

  private static final Http2FrameLogger logger = new Http2FrameLogger(INFO, Initializer.class);

  private final SslContext sslCtx;
  private final int maxContentLength;
  private HttpToHttp2ConnectionHandler connectionHandler;
  private ResponseHandler responseHandler;
  private SettingsHandler settingsHandler;

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
    responseHandler = new ResponseHandler();
    settingsHandler = new SettingsHandler(ch.newPromise());
    ChannelPipeline pipeline = ch.pipeline();
    pipeline.addLast(sslCtx.newHandler(ch.alloc()), connectionHandler, settingsHandler, responseHandler);
  }

  public ResponseHandler responseHandler() {
    return responseHandler;
  }

  public SettingsHandler settingsHandler() {
    return settingsHandler;
  }

}