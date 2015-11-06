package io.norberg.h2client;

import com.spotify.netty4.util.BatchFlusher;
import com.twitter.hpack.Encoder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.http2.DefaultHttp2FrameReader;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Flags;
import io.netty.handler.codec.http2.Http2FrameListener;
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.Http2FrameReader;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2InboundFrameLogger;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.ssl.SslContext;
import io.netty.util.ByteString;
import io.netty.util.collection.IntObjectHashMap;

import static io.netty.handler.codec.http2.Http2CodecUtil.DEFAULT_HEADER_TABLE_SIZE;
import static io.netty.handler.codec.http2.Http2CodecUtil.FRAME_HEADER_LENGTH;
import static io.netty.handler.codec.http2.Http2CodecUtil.INT_FIELD_LENGTH;
import static io.netty.handler.codec.http2.Http2CodecUtil.SETTING_ENTRY_LENGTH;
import static io.netty.handler.codec.http2.Http2CodecUtil.WINDOW_UPDATE_FRAME_LENGTH;
import static io.netty.handler.codec.http2.Http2CodecUtil.readUnsignedInt;
import static io.netty.handler.codec.http2.Http2Error.PROTOCOL_ERROR;
import static io.netty.handler.codec.http2.Http2Flags.END_HEADERS;
import static io.netty.handler.codec.http2.Http2Flags.END_STREAM;
import static io.netty.handler.codec.http2.Http2FrameLogger.Direction.INBOUND;
import static io.netty.handler.codec.http2.Http2FrameTypes.DATA;
import static io.netty.handler.codec.http2.Http2FrameTypes.HEADERS;
import static io.netty.handler.codec.http2.Http2FrameTypes.SETTINGS;
import static io.netty.handler.codec.http2.Http2FrameTypes.WINDOW_UPDATE;
import static io.netty.handler.logging.LogLevel.TRACE;
import static io.norberg.h2client.Http2WireFormat.CLIENT_PREFACE;
import static io.norberg.h2client.Http2WireFormat.writeFrameHeader;
import static io.norberg.h2client.Util.connectionError;

class ClientConnection {

  private static final Logger log = LoggerFactory.getLogger(ClientConnection.class);

  private static final long DEFAULT_MAX_CONCURRENT_STREAMS = 100;
  private static final int DEFAULT_MAX_FRAME_SIZE = 1024 * 1024;

  private final CompletableFuture<ClientConnection> connectFuture = new CompletableFuture<>();
  private final CompletableFuture<ClientConnection> disconnectFuture = new CompletableFuture<>();

  private final IntObjectHashMap<Stream> streams = new IntObjectHashMap<>();

  private final Channel channel;
  private final BatchFlusher flusher;

  private int maxFrameSize = DEFAULT_MAX_FRAME_SIZE;
  private long maxConcurrentStreams = DEFAULT_MAX_CONCURRENT_STREAMS;

  private volatile boolean connected;

  ClientConnection(final String host, final int port, final EventLoopGroup workerGroup, final SslContext sslCtx) {

    // Connect
    final Bootstrap b = new Bootstrap()
        .group(workerGroup)
        .channel(NioSocketChannel.class)
        .option(ChannelOption.SO_KEEPALIVE, true)
        .remoteAddress(host, port)
        .handler(new Initializer(sslCtx, Integer.MAX_VALUE))
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000);

    final ChannelFuture future = b.connect();

    // Propagate connection failure
    future.addListener(f -> {
      if (!future.isSuccess()) {
        connectFuture.completeExceptionally(new ConnectionClosedException(future.cause()));
      }
    });

    this.channel = future.channel();
    this.flusher = new BatchFlusher(channel);
  }

  void send(final Http2Request request, final CompletableFuture<Http2Response> future) {
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

  ChannelFuture close() {
    return channel.close();
  }

  ChannelFuture closeFuture() {
    return channel.closeFuture();
  }

  private Stream stream(final int streamId) throws Http2Exception {
    final Stream stream = streams.get(streamId);
    if (stream == null) {
      throw connectionError(PROTOCOL_ERROR, "Unknown stream id %d", streamId);
    }
    return stream;
  }

  private class Initializer extends ChannelInitializer<SocketChannel> {

    private final Http2FrameLogger logger = new Http2FrameLogger(TRACE, Initializer.class);

    private final SslContext sslCtx;
    private final int maxContentLength;

    public Initializer(SslContext sslCtx, int maxContentLength) {
      this.sslCtx = Objects.requireNonNull(sslCtx, "sslCtx");
      this.maxContentLength = maxContentLength;
    }

    @Override
    public void initChannel(SocketChannel ch) throws Exception {
      final Http2FrameReader reader = new Http2InboundFrameLogger(new DefaultHttp2FrameReader(true), logger);
      final Http2Settings settings = new Http2Settings();
      ch.pipeline().addLast(
          sslCtx.newHandler(ch.alloc()),
          new PrefaceHandler(settings, logger),
          new ConnectionHandler(ch, reader),
          new WriteHandler(),
          new ExceptionHandler());
    }
  }

  private class PrefaceHandler extends ByteToMessageDecoder {

    private final Http2Settings settings;
    private final Http2FrameLogger logger;

    private boolean readHeader;

    private int length;

    private PrefaceHandler(final Http2Settings settings, final Http2FrameLogger logger) {
      this.settings = settings;
      this.logger = logger;
    }

    @Override
    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
      ctx.write(Unpooled.wrappedBuffer(CLIENT_PREFACE.array()));
      writeSettings(ctx);
      ctx.flush();
    }

    private void writeSettings(final ChannelHandlerContext ctx) {
      final ByteBuf buf = ctx.alloc().buffer(FRAME_HEADER_LENGTH);
      writeFrameHeader(buf, 0, 0, SETTINGS, 0, 0);
      buf.writerIndex(FRAME_HEADER_LENGTH);
      ctx.write(buf);
    }

    @Override
    protected void decode(final ChannelHandlerContext ctx, final ByteBuf in, final List<Object> out)
        throws Exception {

      // Read settings frame header
      if (!readHeader) {
        if (in.readableBytes() < FRAME_HEADER_LENGTH) {
          return;
        }
        this.length = in.readUnsignedMedium();
        if (length > maxFrameSize) {
          throw connectionError(PROTOCOL_ERROR, "Frame length: %d exceeds maximum: %d", length, maxFrameSize);
        }
        final byte type = in.readByte();
        if (type != SETTINGS) {
          throw connectionError(PROTOCOL_ERROR, "Expected settings preface, got: %d", type);
        }
        final short flags = in.readUnsignedByte();
        if (flags != 0) {
          throw connectionError(PROTOCOL_ERROR, "Unexpected settings preface flags: %d", flags);
        }
        final int streamId = readUnsignedInt(in);
        if (streamId != 0) {
          throw connectionError(PROTOCOL_ERROR, "Unexpected settings preface stream id: %d", streamId);
        }
        readHeader = true;
      }

      // Read settings frame payload
      if (in.readableBytes() < length) {
        return;
      }
      final Http2Settings settings = new Http2Settings();
      final int n = length / SETTING_ENTRY_LENGTH;
      for (int i = 0; i < n; i++) {
        final char id = (char) in.readUnsignedShort();
        final long value = in.readUnsignedInt();
        settings.put(id, Long.valueOf(value));
      }
      logger.logSettings(INBOUND, settings);

      if (settings.maxFrameSize() != null) {
        maxFrameSize = settings.maxFrameSize();
      }
      if (settings.maxConcurrentStreams() != null) {
        maxConcurrentStreams = settings.maxConcurrentStreams();
      }

      // Remove this handler
      ctx.pipeline().remove(this);

      // Signal that handshake is complete
      connected = true;
      connectFuture.complete(ClientConnection.this);
    }
  }

  private class ExceptionHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
      log.error("caught exception, closing connection", cause);
      ctx.close();
    }
  }

  private class ConnectionHandler extends ByteToMessageDecoder implements Http2FrameListener {

    private final Channel channel;
    private final Http2FrameReader reader;

    private BatchFlusher flusher;

    private ConnectionHandler(final Channel channel, final Http2FrameReader reader) {
      this.channel = channel;
      this.reader = reader;
      this.flusher = new BatchFlusher(channel);
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
      final Exception exception = new ConnectionClosedException();
      streams.forEach((streamId, request) -> request.responseFuture.completeExceptionally(exception));
      disconnectFuture.complete(ClientConnection.this);
    }

    @Override
    protected void decode(final ChannelHandlerContext ctx, final ByteBuf in, final List<Object> out)
        throws Exception {
      reader.readFrame(ctx, in, this);
    }


    @Override
    public int onDataRead(final ChannelHandlerContext ctx, final int streamId, final ByteBuf data, final int padding,
                          final boolean endOfStream)
        throws Http2Exception {
      log.debug("got data: streamId={}, data={}, padding={}, endOfStream={}", streamId, data, padding, endOfStream);
      final Stream stream = stream(streamId);
      final int length = data.readableBytes();

      // TODO: allow user to provide codec that can be used to parse payload directly without copying it

      ByteBuf content = stream.response.content();
      if (content == null) {
        content = ctx.alloc().buffer(length);
        stream.response.content(content);
      }
      content.writeBytes(data);
      maybeDispatch(ctx, endOfStream, stream);

      // TODO: send window updates only when needed
      final ByteBuf buf = ctx.alloc().buffer(WINDOW_UPDATE_FRAME_LENGTH * 2);
      writeWindowUpdate(buf, 0, length);
      writeWindowUpdate(buf, streamId, length);
      ctx.write(buf);
      flusher.flush();

      return length + padding;
    }

    private void writeWindowUpdate(final ByteBuf buf, final int streamId, final int sizeIncrement) {
      final int offset = buf.writerIndex();
      buf.ensureWritable(FRAME_HEADER_LENGTH);
      writeFrameHeader(buf, offset, INT_FIELD_LENGTH, WINDOW_UPDATE, 0, streamId);
      buf.writerIndex(offset + FRAME_HEADER_LENGTH);
      buf.writeInt(sizeIncrement);
    }

    @Override
    public void onHeadersRead(final ChannelHandlerContext ctx, final int streamId, final Http2Headers headers,
                              final int padding, final boolean endOfStream) throws Http2Exception {
      log.debug("got headers: streamId={}, headers={}, padding={}, endOfStream={}",
                streamId, headers, padding, endOfStream);
      final Stream stream = stream(streamId);
      stream.response.headers(headers);
      maybeDispatch(ctx, endOfStream, stream);
    }

    @Override
    public void onHeadersRead(final ChannelHandlerContext ctx, final int streamId, final Http2Headers headers,
                              final int streamDependency, final short weight, final boolean exclusive,
                              final int padding, final boolean endOfStream)
        throws Http2Exception {
      log.debug("got headers: streamId={}, headers={}, streamDependency={}, weight={}, exclusive={}, padding={}, "
                + "endOfStream={}", streamId, headers, streamDependency, weight, exclusive, padding, endOfStream);
      final Stream stream = stream(streamId);
      stream.response.headers(headers);
      maybeDispatch(ctx, endOfStream, stream);
    }

    @Override
    public void onPriorityRead(final ChannelHandlerContext ctx, final int streamId, final int streamDependency,
                               final short weight, final boolean exclusive) throws Http2Exception {
      log.debug("got priority: streamId={}, streamDependency={}, weight={}, exclusive={}",
                streamId, streamDependency, weight, exclusive);
    }

    @Override
    public void onRstStreamRead(final ChannelHandlerContext ctx, final int streamId, final long errorCode)
        throws Http2Exception {
      log.debug("got rst stream: streamId={}, errorCode={}", streamId, errorCode);
    }

    @Override
    public void onSettingsAckRead(final ChannelHandlerContext ctx) throws Http2Exception {
      log.debug("got settings ack");
    }

    @Override
    public void onSettingsRead(final ChannelHandlerContext ctx, final Http2Settings settings) throws Http2Exception {
      log.debug("got settings: {}", settings);
      if (settings.maxFrameSize() != null) {
        maxFrameSize = settings.maxFrameSize();
      }
      if (settings.maxConcurrentStreams() != null) {
        maxConcurrentStreams = settings.maxConcurrentStreams();
      }
    }

    @Override
    public void onPingRead(final ChannelHandlerContext ctx, final ByteBuf data) throws Http2Exception {
      log.debug("got ping");
      // TODO: ack
    }

    @Override
    public void onPingAckRead(final ChannelHandlerContext ctx, final ByteBuf data) throws Http2Exception {
      log.debug("got ping ack");
    }

    @Override
    public void onPushPromiseRead(final ChannelHandlerContext ctx, final int streamId, final int promisedStreamId,
                                  final Http2Headers headers,
                                  final int padding) throws Http2Exception {
      log.debug("got push promise");
    }

    @Override
    public void onGoAwayRead(final ChannelHandlerContext ctx, final int lastStreamId, final long errorCode,
                             final ByteBuf debugData)
        throws Http2Exception {
      log.debug("got goaway, closing connection");
      ctx.close();
    }

    @Override
    public void onWindowUpdateRead(final ChannelHandlerContext ctx, final int streamId, final int windowSizeIncrement)
        throws Http2Exception {
      log.debug("got window update: streamId={}, windowSizeIncrement={}", streamId, windowSizeIncrement);
    }

    @Override
    public void onUnknownFrame(final ChannelHandlerContext ctx, final byte frameType, final int streamId,
                               final Http2Flags flags, final ByteBuf payload)
        throws Http2Exception {
      log.debug("got unknown frame: {} {} {} {}", frameType, streamId, flags, payload);
    }

    private void maybeDispatch(final ChannelHandlerContext ctx, final boolean endOfStream,
                               final Stream stream) {
      if (!endOfStream) {
        return;
      }

      streams.remove(stream.id);

      stream.responseFuture.complete(stream.response);
    }
  }

  private class RequestPromise extends DefaultChannelPromise {

    private final CompletableFuture<Http2Response> responseFuture;

    public RequestPromise(final Channel channel, final CompletableFuture<Http2Response> responseFuture) {
      super(channel);
      this.responseFuture = responseFuture;
      // TODO: override completion methods to avoid listener garbage
      addListener(f -> {
        if (!isSuccess()) {
          responseFuture.completeExceptionally(cause());
        }
      });
    }
  }

  private static class Stream {

    final int id;
    private final CompletableFuture<Http2Response> responseFuture;

    final Http2Response response = new Http2Response();

    public Stream(final int id, final CompletableFuture<Http2Response> responseFuture) {

      this.id = id;
      this.responseFuture = responseFuture;
    }
  }

  private class WriteHandler extends ChannelOutboundHandlerAdapter {

    private final Encoder headerEncoder = new Encoder(DEFAULT_HEADER_TABLE_SIZE);

    private int streamId = 1;

    private int nextStreamId() {
      streamId += 2;
      return streamId;
    }

    @Override
    public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise)
        throws Exception {
      final Http2Request request = (Http2Request) msg;
      final RequestPromise requestPromise = (RequestPromise) promise;

      // Already at max concurrent streams? Fail fast.
      if (streams.size() >= maxConcurrentStreams) {
        requestPromise.responseFuture.completeExceptionally(new MaxConcurrentStreamsLimitReachedException());
        return;
      }

      // Generate ID and store response future in stream map to correlate with responses
      final int streamId = nextStreamId();
      request.streamId(streamId);
      final Stream stream = new Stream(streamId, requestPromise.responseFuture);
      streams.put(streamId, stream);

      log.debug("sending request: {}", request);
      final boolean hasContent = request.hasContent();
      final ByteBuf buf = ctx.alloc().buffer();
      try {
        writeHeaders(buf, request.streamId(), request.headers(), !hasContent);
      } catch (IOException e) {
        ctx.fireExceptionCaught(e);
        return;
      }
      if (hasContent) {
        writeData(buf, request.streamId(), request.content(), true);
      }
      ctx.write(buf);
      flusher.flush();
    }

    private void writeHeaders(final ByteBuf buf, int streamId, Http2Headers headers, boolean endStream)
        throws IOException {
      final int headerIndex = buf.writerIndex();

      buf.ensureWritable(FRAME_HEADER_LENGTH);
      buf.writerIndex(headerIndex + FRAME_HEADER_LENGTH);
      final int size = encodeHeaders(headers, buf);

      if (size > maxFrameSize) {
        // TODO: continuation frames
        throw new AssertionError();
      }

      final int flags = END_HEADERS | (endStream ? END_STREAM : 0);
      writeFrameHeader(buf, headerIndex, size, HEADERS, flags, streamId);

      // TODO: padding + fields
    }

    public void writeData(ByteBuf buf, int streamId, ByteBuf data, boolean endStream) {
      final int headerIndex = buf.writerIndex();
      int payloadLength = data.readableBytes();
      final int flags = endStream ? END_STREAM : 0;
      buf.ensureWritable(FRAME_HEADER_LENGTH);
      writeFrameHeader(buf, headerIndex, payloadLength, DATA, flags, streamId);
      buf.writerIndex(headerIndex + FRAME_HEADER_LENGTH);
      // TODO: padding + fields
      buf.writeBytes(data);
    }

    private int encodeHeaders(Http2Headers headers, ByteBuf buffer) throws IOException {
      final ByteBufOutputStream stream = new ByteBufOutputStream(buffer);
      for (Map.Entry<ByteString, ByteString> header : headers) {
        headerEncoder.encodeHeader(stream, toBytes(header.getKey()), toBytes(header.getValue()), false);
      }
      return stream.writtenBytes();
    }

    private byte[] toBytes(ByteString s) {
      if (s.isEntireArrayUsed()) {
        return s.array();
      } else {
        return s.toByteArray();
      }
    }
  }
}
