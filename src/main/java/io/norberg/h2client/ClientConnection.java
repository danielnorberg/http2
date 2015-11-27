package io.norberg.h2client;

import com.spotify.netty4.util.BatchFlusher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
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
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Flags;
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.ssl.SslContext;
import io.netty.util.AsciiString;
import io.netty.util.collection.IntObjectHashMap;

import static io.netty.handler.codec.http2.Http2CodecUtil.DEFAULT_HEADER_TABLE_SIZE;
import static io.netty.handler.codec.http2.Http2CodecUtil.DEFAULT_WINDOW_SIZE;
import static io.netty.handler.codec.http2.Http2CodecUtil.FRAME_HEADER_LENGTH;
import static io.netty.handler.codec.http2.Http2CodecUtil.INT_FIELD_LENGTH;
import static io.netty.handler.codec.http2.Http2CodecUtil.SETTING_ENTRY_LENGTH;
import static io.netty.handler.codec.http2.Http2CodecUtil.WINDOW_UPDATE_FRAME_LENGTH;
import static io.netty.handler.codec.http2.Http2Error.PROTOCOL_ERROR;
import static io.netty.handler.codec.http2.Http2Flags.ACK;
import static io.netty.handler.codec.http2.Http2Flags.END_HEADERS;
import static io.netty.handler.codec.http2.Http2Flags.END_STREAM;
import static io.netty.handler.codec.http2.Http2FrameTypes.DATA;
import static io.netty.handler.codec.http2.Http2FrameTypes.HEADERS;
import static io.netty.handler.codec.http2.Http2FrameTypes.SETTINGS;
import static io.netty.handler.codec.http2.Http2FrameTypes.WINDOW_UPDATE;
import static io.netty.handler.logging.LogLevel.TRACE;
import static io.norberg.h2client.Http2WireFormat.CLIENT_PREFACE;
import static io.norberg.h2client.Http2WireFormat.writeFrameHeader;
import static io.norberg.h2client.Util.connectionError;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

class ClientConnection {

  private static final Logger log = LoggerFactory.getLogger(ClientConnection.class);

  private static final int DEFAULT_LOCAL_WINDOW_SIZE = 1024 * 1024 * 128;

  private final CompletableFuture<ClientConnection> connectFuture = new CompletableFuture<>();
  private final CompletableFuture<ClientConnection> disconnectFuture = new CompletableFuture<>();

  private final HpackEncoder headerEncoder = new HpackEncoder(DEFAULT_HEADER_TABLE_SIZE);
  private final IntObjectHashMap<Stream> streams = new IntObjectHashMap<>();

  private final Channel channel;
  private final BatchFlusher flusher;
  private final Listener listener;

  private int maxFrameSize;
  private int maxConcurrentStreams;
  private int remoteInitialWindowSize = DEFAULT_WINDOW_SIZE;
  private int maxHeaderListSize = Integer.MAX_VALUE;

  private int initialLocalWindow = DEFAULT_LOCAL_WINDOW_SIZE;
  private int localWindowUpdateThreshold = initialLocalWindow / 2;
  private int localWindow = initialLocalWindow;

  private volatile boolean connected;

  ClientConnection(final Builder builder) {
    this.listener = requireNonNull(builder.listener, "listener");

    this.maxFrameSize = builder.maxFrameSize;
    this.maxConcurrentStreams = builder.maxConcurrentStreams;

    // Connect
    final Bootstrap b = new Bootstrap()
        .group(requireNonNull(builder.workerGroup, "workerGroup"))
        .channel(NioSocketChannel.class)
        .option(ChannelOption.SO_KEEPALIVE, true)
        .remoteAddress(requireNonNull(builder.address, "address"))
        .handler(new Initializer(requireNonNull(builder.sslContext, "sslContext"), Integer.MAX_VALUE))
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


  void send(final Http2Request request, final Http2ResponseHandler responseHandler) {
    assert connected;
    final ChannelPromise promise = new RequestPromise(channel, responseHandler);
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
      this.sslCtx = requireNonNull(sslCtx, "sslCtx");
      this.maxContentLength = maxContentLength;
    }

    @Override
    public void initChannel(SocketChannel ch) throws Exception {
      final Http2Settings settings = new Http2Settings();
      ch.pipeline().addLast(
          sslCtx.newHandler(ch.alloc()),
          new ConnectionHandler(ch),
          new WriteHandler(),
          new ExceptionHandler());
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

    private final Http2FrameReader reader;

    private BatchFlusher flusher;

    // Current stream
    private Stream stream;

    private ConnectionHandler(final Channel channel) {
      this.reader = new Http2FrameReader(new HpackDecoder(DEFAULT_HEADER_TABLE_SIZE), this);
      this.flusher = new BatchFlusher(channel);
    }

    @Override
    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
      ctx.write(Unpooled.wrappedBuffer(CLIENT_PREFACE.array()));
      writeSettings(ctx);

      // Update channel window size
      final ByteBuf buf = ctx.alloc().buffer(WINDOW_UPDATE_FRAME_LENGTH);
      final int sizeIncrement = initialLocalWindow - DEFAULT_WINDOW_SIZE;
      writeWindowUpdate(buf, 0, sizeIncrement);
      ctx.write(buf);

      flusher.flush();
      connected = true;
      connectFuture.complete(ClientConnection.this);
    }

    private void writeSettings(final ChannelHandlerContext ctx) {
      final ByteBuf buf = ctx.alloc().buffer(FRAME_HEADER_LENGTH);
      final Http2Settings settings = new Http2Settings();
      settings.initialWindowSize(initialLocalWindow);
      settings.maxConcurrentStreams(maxConcurrentStreams);
      settings.maxFrameSize(maxFrameSize);
      final int length = SETTING_ENTRY_LENGTH * settings.size();
      writeFrameHeader(buf, 0, length, SETTINGS, 0, 0);
      buf.writerIndex(FRAME_HEADER_LENGTH);
      for (final char identifier : settings.keySet()) {
        final int value = settings.getIntValue(identifier);
        buf.writeShort(identifier);
        buf.writeInt(value);
      }
      ctx.write(buf);
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
      final Exception exception = new ConnectionClosedException();
      streams.forEach((streamId, stream) -> fail(stream.responseHandler, exception));
      disconnectFuture.complete(ClientConnection.this);
    }

    @Override
    protected void decode(final ChannelHandlerContext ctx, final ByteBuf in, final List<Object> out)
        throws Exception {
      reader.readFrames(ctx, in);
    }

    @Override
    public int onDataRead(final ChannelHandlerContext ctx, final int streamId, final ByteBuf data, final int padding,
                          final boolean endOfStream)
        throws Http2Exception {
      log.debug("got data: streamId={}, data={}, padding={}, endOfStream={}", streamId, data, padding, endOfStream);
      final Stream stream = stream(streamId);
      final int length = data.readableBytes();
      stream.localWindow -= length;
      localWindow -= length;

      // TODO: allow user to provide codec that can be used to parse payload directly without copying it

      ByteBuf content = stream.response.content();
      if (content == null) {
        content = ctx.alloc().buffer(length);
        stream.response.content(content);
      }
      content.writeBytes(data);
      maybeDispatch(ctx, endOfStream, stream);

      final boolean updateChannelWindow = localWindow < localWindowUpdateThreshold;
      final boolean updateStreamWindow = !endOfStream && stream.localWindow < localWindowUpdateThreshold;
      if (updateChannelWindow || updateStreamWindow) {
        final int bufSize = (updateChannelWindow ? WINDOW_UPDATE_FRAME_LENGTH : 0) +
                            (updateStreamWindow ? WINDOW_UPDATE_FRAME_LENGTH : 0);
        final ByteBuf buf = ctx.alloc().buffer(bufSize);
        if (updateChannelWindow) {
          final int sizeIncrement = initialLocalWindow - localWindow;
          localWindow = initialLocalWindow;
          writeWindowUpdate(buf, 0, sizeIncrement);
        }
        if (updateStreamWindow) {
          final int sizeIncrement = initialLocalWindow - stream.localWindow;
          stream.localWindow = initialLocalWindow;
          writeWindowUpdate(buf, streamId, sizeIncrement);
        }
        ctx.write(buf);
        flusher.flush();
      }

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
      assert headers == null;
      log.debug("got headers: streamId={}, padding={}, endOfStream={}",
                streamId, padding, endOfStream);
      this.stream = stream(streamId);
    }

    @Override
    public void onHeadersRead(final ChannelHandlerContext ctx, final int streamId, final Http2Headers headers,
                              final int streamDependency, final short weight, final boolean exclusive,
                              final int padding, final boolean endOfStream)
        throws Http2Exception {
      assert headers == null;
      log.debug("got headers: streamId={}, streamDependency={}, weight={}, exclusive={}, padding={}, "
                + "endOfStream={}", streamId, streamDependency, weight, exclusive, padding, endOfStream);
      this.stream = stream(streamId);
    }

    @Override
    public void onHeaderRead(final Http2Header header) throws Http2Exception {
      assert stream != null;
      final AsciiString name = header.name();
      final AsciiString value = header.value();
      if (name.byteAt(0) == ':') {
        readPseudoHeader(name, value);
      } else {
        stream.response.header(name, value);
      }
    }

    private void readPseudoHeader(final AsciiString name, final AsciiString value) throws Http2Exception {
      assert stream != null;
      if (!name.equals(Http2Headers.PseudoHeaderName.STATUS.value())) {
        throw new Http2Exception(PROTOCOL_ERROR);
      }
      stream.response.status(HttpResponseStatus.valueOf(value.parseInt()));
    }

    @Override
    public void onHeadersEnd(final ChannelHandlerContext ctx, final int streamId, final boolean endOfStream) throws Http2Exception {
      assert stream != null;
      maybeDispatch(ctx, endOfStream, stream);
      stream = null;
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
        maxConcurrentStreams = settings.maxConcurrentStreams().intValue();
      }
      if (settings.initialWindowSize() != null) {
        // TODO: apply delta etc
        remoteInitialWindowSize = settings.initialWindowSize();
      }
      if (settings.maxHeaderListSize() != null) {
        maxHeaderListSize = settings.maxHeaderListSize();
      }
      // TODO: SETTINGS_HEADER_TABLE_SIZE
      // TODO: SETTINGS_ENABLE_PUSH

      listener.peerSettingsChanged(ClientConnection.this, settings);

      sendSettingsAck(ctx);
    }

    private void sendSettingsAck(final ChannelHandlerContext ctx) {
      final ByteBuf buf = ctx.alloc().buffer(FRAME_HEADER_LENGTH);
      writeFrameHeader(buf, 0, 0, SETTINGS, ACK, 0);
      buf.writerIndex(FRAME_HEADER_LENGTH);
      ctx.write(buf);
      flusher.flush();
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
      log.error("got goaway, closing connection: lastStreamId={}, errorCode={}, debugData={}",
                lastStreamId, errorCode, debugData.toString(UTF_8));
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

      succeed(stream.responseHandler, stream.response);
    }
  }

  private void succeed(final Http2ResponseHandler responseHandler, final Http2Response response) {
    listener.responseReceived(ClientConnection.this, response);
    responseHandler.response(response);
  }

  private void fail(final Http2ResponseHandler responseHandler, final Throwable t) {
    listener.requestFailed(ClientConnection.this);
    responseHandler.failure(t);
  }

  private class RequestPromise extends DefaultChannelPromise {

    private final Http2ResponseHandler responseHandler;

    public RequestPromise(final Channel channel, final Http2ResponseHandler responseHandler) {
      super(channel);
      this.responseHandler = responseHandler;
    }

    @Override
    public ChannelPromise setFailure(final Throwable cause) {
      fail(responseHandler, cause);
      return super.setFailure(cause);
    }
  }

  private static class Stream {

    final int id;
    final Http2ResponseHandler responseHandler;
    final Http2Response response = new Http2Response();

    int localWindow;

    public Stream(final int id, final Http2ResponseHandler responseHandler, final int localWindow) {
      this.id = id;
      this.responseHandler = responseHandler;
      this.localWindow = localWindow;
    }
  }

  private class WriteHandler extends ChannelOutboundHandlerAdapter {

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
        fail(requestPromise.responseHandler, new MaxConcurrentStreamsLimitReachedException());
        return;
      }

      // Generate ID and store response future in stream map to correlate with responses
      final int streamId = nextStreamId();
      request.streamId(streamId);
      final Stream stream = new Stream(streamId, requestPromise.responseHandler, initialLocalWindow);
      streams.put(streamId, stream);

      log.debug("sending request: {}", request);
      final boolean hasContent = request.hasContent();
      final ByteBuf buf = ctx.alloc().buffer();
      writeHeaders(buf, request.streamId(), request, !hasContent);
      if (hasContent) {
        writeData(buf, request.streamId(), request.content(), true);
      }
      ctx.write(buf);
      flusher.flush();
    }

    private void writeHeaders(final ByteBuf buf, int streamId, Http2Request request, boolean endStream)
        throws HpackEncodingException {
      final int headerIndex = buf.writerIndex();

      buf.ensureWritable(FRAME_HEADER_LENGTH);
      buf.writerIndex(headerIndex + FRAME_HEADER_LENGTH);
      final int size = encodeHeaders(request, buf);

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

    private int encodeHeaders(Http2Request request, ByteBuf buffer) throws HpackEncodingException {
      final int mark = buffer.readableBytes();
      headerEncoder.encodeRequest(buffer,
                                  request.method().asciiName(),
                                  request.scheme(),
                                  request.authority(),
                                  request.path());
      if (request.hasHeaders()) {
        for (Map.Entry<CharSequence, CharSequence> header : request.headers()) {
          final AsciiString name = AsciiString.of(header.getKey());
          final AsciiString value = AsciiString.of(header.getValue());
          headerEncoder.encodeHeader(buffer, name, value, false);
        }
      }
      final int size = buffer.readableBytes() - mark;
      return size;
    }

    private byte[] toBytes(CharSequence s) {
      final AsciiString as = AsciiString.of(s);
      if (as.isEntireArrayUsed()) {
        return as.array();
      } else {
        return as.toByteArray();
      }
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {

    private InetSocketAddress address;
    private Listener listener;
    private SslContext sslContext;
    private EventLoopGroup workerGroup;

    private int maxConcurrentStreams;
    private int maxFrameSize;

    public Builder address(final InetSocketAddress address) {
      this.address = address;
      return this;
    }

    public Builder listener(final Listener listener) {
      this.listener = listener;
      return this;
    }

    public Builder workerGroup(final EventLoopGroup workerGroup) {
      this.workerGroup = workerGroup;
      return this;
    }

    public Builder sslContext(final SslContext sslContext) {
      this.sslContext = sslContext;
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

    public ClientConnection build() {
      return new ClientConnection(this);
    }
  }

  interface Listener {

    /**
     * Called when remote peer settings changed.
     */
    void peerSettingsChanged(ClientConnection connection, Http2Settings settings);

    void requestFailed(ClientConnection connection);

    void responseReceived(ClientConnection connection, Http2Response response);
  }
}
