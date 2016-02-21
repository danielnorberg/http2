package io.norberg.h2client;

import com.spotify.netty.util.BatchFlusher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Flags;
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.AsciiString;

import static io.netty.handler.codec.http2.Http2CodecUtil.DEFAULT_HEADER_TABLE_SIZE;
import static io.netty.handler.codec.http2.Http2CodecUtil.DEFAULT_WINDOW_SIZE;
import static io.netty.handler.codec.http2.Http2CodecUtil.FRAME_HEADER_LENGTH;
import static io.netty.handler.codec.http2.Http2CodecUtil.INT_FIELD_LENGTH;
import static io.netty.handler.codec.http2.Http2CodecUtil.PING_FRAME_PAYLOAD_LENGTH;
import static io.netty.handler.codec.http2.Http2CodecUtil.SETTING_ENTRY_LENGTH;
import static io.netty.handler.codec.http2.Http2CodecUtil.WINDOW_UPDATE_FRAME_LENGTH;
import static io.netty.handler.codec.http2.Http2Error.PROTOCOL_ERROR;
import static io.netty.handler.codec.http2.Http2Flags.ACK;
import static io.netty.handler.codec.http2.Http2Flags.END_HEADERS;
import static io.netty.handler.codec.http2.Http2Flags.END_STREAM;
import static io.netty.handler.codec.http2.Http2FrameTypes.DATA;
import static io.netty.handler.codec.http2.Http2FrameTypes.HEADERS;
import static io.netty.handler.codec.http2.Http2FrameTypes.PING;
import static io.netty.handler.codec.http2.Http2FrameTypes.SETTINGS;
import static io.netty.handler.codec.http2.Http2FrameTypes.WINDOW_UPDATE;
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.AUTHORITY;
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.METHOD;
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.PATH;
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.SCHEME;
import static io.netty.handler.logging.LogLevel.TRACE;
import static io.norberg.h2client.Http2Protocol.DEFAULT_INITIAL_WINDOW_SIZE;
import static io.norberg.h2client.Http2WireFormat.CLIENT_PREFACE;
import static io.norberg.h2client.Http2WireFormat.FRAME_HEADER_SIZE;
import static io.norberg.h2client.Http2WireFormat.writeFrameHeader;
import static java.lang.Integer.max;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

class ClientConnection {

  private static final Logger log = LoggerFactory.getLogger(ClientConnection.class);

  private final CompletableFuture<ClientConnection> connectFuture = new CompletableFuture<>();
  private final CompletableFuture<ClientConnection> disconnectFuture = new CompletableFuture<>();

  private final HpackEncoder headerEncoder = new HpackEncoder(DEFAULT_HEADER_TABLE_SIZE);

  private final StreamController<ClientStream> streamController = new StreamController<>();
  private final FlowController<ChannelHandlerContext, ClientStream> flowController = new FlowController<>();

  private final InetSocketAddress address;
  private final SslContext sslContext;
  private final EventLoop worker;
  private final Channel channel;
  private final BatchFlusher flusher;
  private final Listener listener;

  private final Http2Settings localSettings = new Http2Settings();

  private int remoteMaxFrameSize = Http2CodecUtil.DEFAULT_MAX_FRAME_SIZE;
  private int remoteMaxConcurrentStreams = Integer.MAX_VALUE;
  private int remoteMaxHeaderListSize = Integer.MAX_VALUE;

  private final int localInitialStreamWindow;
  private final int localMaxConnectionWindow;

  private final int localConnectionWindowUpdateThreshold;
  private final int localStreamWindowUpdateThreshold;

  private int localConnectionWindow;

  ClientConnection(final Builder builder) {
    this.listener = requireNonNull(builder.listener, "listener");

    this.localInitialStreamWindow = Optional.ofNullable(builder.initialStreamWindowSize)
        .orElse(DEFAULT_INITIAL_WINDOW_SIZE);
    this.localMaxConnectionWindow = Optional.ofNullable(builder.connectionWindowSize)
        .orElse(DEFAULT_INITIAL_WINDOW_SIZE);
    this.localConnectionWindow = max(localMaxConnectionWindow, DEFAULT_INITIAL_WINDOW_SIZE);
    this.localStreamWindowUpdateThreshold = (localInitialStreamWindow + 1) / 2;
    this.localConnectionWindowUpdateThreshold = (localMaxConnectionWindow + 1) / 2;

    if (builder.initialStreamWindowSize != null) {
      localSettings.initialWindowSize(builder.initialStreamWindowSize);
    }
    if (builder.maxConcurrentStreams != null) {
      localSettings.maxConcurrentStreams(builder.maxConcurrentStreams);
    }
    if (builder.maxFrameSize != null) {
      localSettings.maxFrameSize(builder.maxFrameSize);
    }

    this.address = requireNonNull(builder.address, "address");
    this.sslContext = requireNonNull(builder.sslContext, "sslContext");
    this.worker = requireNonNull(builder.worker, "worker");
    this.channel = new NioSocketChannel();
    this.flusher = BatchFlusher.of(channel, worker);
  }

  CompletableFuture<ClientConnection> connect() {

    final Bootstrap b = new Bootstrap()
        .group(worker)
        .channelFactory(() -> channel)
        .option(ChannelOption.SO_KEEPALIVE, true)
        .remoteAddress(address)
        .handler(new Initializer(sslContext, Integer.MAX_VALUE))
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000);

    final ChannelFuture future = b.connect();

    // Propagate connection failure
    future.addListener(f -> {
      if (!future.isSuccess()) {
        connectFuture.completeExceptionally(new ConnectionClosedException(future.cause()));
      }
    });

    return connectFuture;
  }


  void send(final Http2Request request, final Http2ResponseHandler responseHandler) {
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

  boolean isDisconnected() {
    return !channel.isActive();
  }

  ChannelFuture close() {
    return channel.close();
  }

  ChannelFuture closeFuture() {
    return channel.closeFuture();
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
      final SslHandler sslHandler = sslCtx.newHandler(ch.alloc());

      // XXX: Discard read bytes well before consolidating
      // https://github.com/netty/netty/commit/c8a941d01e85148c21cc01bae80764bc134b1fdd
      sslHandler.setDiscardAfterReads(7);

      ch.pipeline().addLast(
          sslHandler,
          new ConnectionHandler(),
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

    // Stream currently being read
    private ClientStream stream;

    ConnectionHandler() {
      this.reader = new Http2FrameReader(new HpackDecoder(DEFAULT_HEADER_TABLE_SIZE), this);
    }

    @Override
    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
      super.channelActive(ctx);

      ctx.write(Unpooled.wrappedBuffer(CLIENT_PREFACE.array()));
      writeSettings(ctx);

      // Update connection window size
      final int sizeIncrement = localMaxConnectionWindow - DEFAULT_WINDOW_SIZE;
      if (sizeIncrement > 0) {
        final ByteBuf buf = ctx.alloc().buffer(WINDOW_UPDATE_FRAME_LENGTH);
        writeWindowUpdate(buf, 0, sizeIncrement);
        ctx.write(buf);
      }

      flusher.flush();
      connectFuture.complete(ClientConnection.this);
    }

    private void writeSettings(final ChannelHandlerContext ctx) {
      final int length = SETTING_ENTRY_LENGTH * localSettings.size();
      final ByteBuf buf = ctx.alloc().buffer(FRAME_HEADER_LENGTH + length);
      writeFrameHeader(buf, 0, length, SETTINGS, 0, 0);
      buf.writerIndex(FRAME_HEADER_LENGTH);
      for (final char identifier : localSettings.keySet()) {
        final int value = localSettings.getIntValue(identifier);
        buf.writeShort(identifier);
        buf.writeInt(value);
      }
      ctx.write(buf);
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
      super.channelInactive(ctx);
      final Exception exception = new ConnectionClosedException();
      streamController.forEach(stream -> fail(stream.requestPromise.responseHandler, exception));
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
      if (log.isDebugEnabled()) {
        log.debug("got data: streamId={}, data={}, padding={}, endOfStream={}", streamId, data, padding, endOfStream);
      }
      final ClientStream stream = streamController.existingStream(streamId);
      final int length = data.readableBytes();

      // TODO: allow user to provide codec that can be used to parse payload directly without copying it

      ByteBuf content = stream.response.content();
      if (content == null) {
        // TODO: use pooled buffer or slice?
        content = Unpooled.buffer(length);
        stream.response.content(content);
      }
      content.writeBytes(data);
      maybeDispatch(ctx, endOfStream, stream);

      stream.localWindow -= length;
      localConnectionWindow -= length;

      final boolean updateConnectionWindow = localConnectionWindow < localConnectionWindowUpdateThreshold;
      final boolean updateStreamWindow = !endOfStream && stream.localWindow < localStreamWindowUpdateThreshold;
      if (updateConnectionWindow || updateStreamWindow) {
        final int bufSize = (updateConnectionWindow ? WINDOW_UPDATE_FRAME_LENGTH : 0) +
                            (updateStreamWindow ? WINDOW_UPDATE_FRAME_LENGTH : 0);
        final ByteBuf buf = ctx.alloc().buffer(bufSize);
        if (updateConnectionWindow) {
          final int sizeIncrement = localMaxConnectionWindow - localConnectionWindow;
          localConnectionWindow = localMaxConnectionWindow;
          writeWindowUpdate(buf, 0, sizeIncrement);
        }
        if (updateStreamWindow) {
          final int sizeIncrement = localInitialStreamWindow - stream.localWindow;
          stream.localWindow = localInitialStreamWindow;
          writeWindowUpdate(buf, streamId, sizeIncrement);
        }
        ctx.write(buf);
        flusher.flush();
      }

      return length + padding;
    }

    private void writeWindowUpdate(final ByteBuf buf, final int streamId, final int sizeIncrement) {
      log.debug("writeWindowUpdate: streamId={}, sizeIncrement={}", streamId, sizeIncrement);
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
      if (log.isDebugEnabled()) {
        log.debug("got headers: streamId={}, padding={}, endOfStream={}",
                  streamId, padding, endOfStream);
      }
      this.stream = streamController.existingStream(streamId);
    }

    @Override
    public void onHeadersRead(final ChannelHandlerContext ctx, final int streamId, final Http2Headers headers,
                              final int streamDependency, final short weight, final boolean exclusive,
                              final int padding, final boolean endOfStream)
        throws Http2Exception {
      assert headers == null;
      if (log.isDebugEnabled()) {
        log.debug("got headers: streamId={}, streamDependency={}, weight={}, exclusive={}, padding={}, "
                  + "endOfStream={}", streamId, streamDependency, weight, exclusive, padding, endOfStream);
      }
      this.stream = streamController.existingStream(streamId);
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
    public void onHeadersEnd(final ChannelHandlerContext ctx, final int streamId, final boolean endOfStream)
        throws Http2Exception {
      assert stream != null;
      maybeDispatch(ctx, endOfStream, stream);
      stream = null;
    }

    @Override
    public void onPriorityRead(final ChannelHandlerContext ctx, final int streamId, final int streamDependency,
                               final short weight, final boolean exclusive) throws Http2Exception {
      if (log.isDebugEnabled()) {
        log.debug("got priority: streamId={}, streamDependency={}, weight={}, exclusive={}",
                  streamId, streamDependency, weight, exclusive);
      }
    }

    @Override
    public void onRstStreamRead(final ChannelHandlerContext ctx, final int streamId, final long errorCode)
        throws Http2Exception {
      if (log.isDebugEnabled()) {
        log.debug("got rst stream: streamId={}, errorCode={}", streamId, errorCode);
      }
    }

    @Override
    public void onSettingsAckRead(final ChannelHandlerContext ctx) throws Http2Exception {
      if (log.isDebugEnabled()) {
        log.debug("got settings ack");
      }
    }

    @Override
    public void onSettingsRead(final ChannelHandlerContext ctx, final Http2Settings settings) throws Http2Exception {
      if (log.isDebugEnabled()) {
        log.debug("got settings: {}", settings);
      }
      if (settings.maxFrameSize() != null) {
        remoteMaxFrameSize = settings.maxFrameSize();
        Http2Protocol.validateMaxFrameSize(remoteMaxFrameSize);
        flowController.remoteMaxFrameSize(remoteMaxFrameSize);
      }
      if (settings.maxConcurrentStreams() != null) {
        remoteMaxConcurrentStreams = settings.maxConcurrentStreams().intValue();
      }
      if (settings.initialWindowSize() != null) {
        flowController.remoteInitialStreamWindowSizeUpdate(settings.initialWindowSize(), streamController);
        flusher.flush();
      }
      if (settings.maxHeaderListSize() != null) {
        remoteMaxHeaderListSize = settings.maxHeaderListSize();
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
      if (log.isDebugEnabled()) {
        log.debug("got ping");
      }
      sendPingAck(ctx, data);
    }

    private void sendPingAck(final ChannelHandlerContext ctx, final ByteBuf data) {
      final ByteBuf buf = ctx.alloc().buffer(FRAME_HEADER_LENGTH + PING_FRAME_PAYLOAD_LENGTH);
      writeFrameHeader(buf, 0, PING_FRAME_PAYLOAD_LENGTH, PING, ACK, 0);
      buf.writerIndex(FRAME_HEADER_LENGTH);
      buf.writeBytes(data);
      ctx.write(buf);
      flusher.flush();
    }

    @Override
    public void onPingAckRead(final ChannelHandlerContext ctx, final ByteBuf data) throws Http2Exception {
      if (log.isDebugEnabled()) {
        log.debug("got ping ack");
      }
    }

    @Override
    public void onPushPromiseRead(final ChannelHandlerContext ctx, final int streamId, final int promisedStreamId,
                                  final Http2Headers headers,
                                  final int padding) throws Http2Exception {
      if (log.isDebugEnabled()) {
        log.debug("got push promise");
      }
    }

    @Override
    public void onPushPromiseHeadersEnd(final ChannelHandlerContext ctx, final int streamId) {
      if (log.isDebugEnabled()) {
        log.debug("got push promise headers");
      }
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
      if (log.isDebugEnabled()) {
        log.debug("got window update: streamId={}, windowSizeIncrement={}", streamId, windowSizeIncrement);
      }
      if (streamId == 0) {
        flowController.remoteConnectionWindowUpdate(windowSizeIncrement);
      } else {
        final ClientStream stream = streamController.existingStream(streamId);

        // The stream might already be closed. That's ok.
        if (stream != null) {
          flowController.remoteStreamWindowUpdate(stream, windowSizeIncrement);
        }
      }
      flusher.flush();
    }

    @Override
    public void onUnknownFrame(final ChannelHandlerContext ctx, final byte frameType, final int streamId,
                               final Http2Flags flags, final ByteBuf payload)
        throws Http2Exception {
      if (log.isDebugEnabled()) {
        log.debug("got unknown frame: {} {} {} {}", frameType, streamId, flags, payload);
      }
    }

    private void maybeDispatch(final ChannelHandlerContext ctx, final boolean endOfStream,
                               final ClientStream stream) {
      if (!endOfStream) {
        return;
      }

      streamController.removeStream(stream.id);
      stream.close();

      succeed(stream.requestPromise.responseHandler, stream.response);
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
      super.setFailure(cause);
      fail(responseHandler, cause);
      return this;
    }

    @Override
    public boolean tryFailure(final Throwable cause) {
      final boolean set = super.tryFailure(cause);
      if (set) {
        final Throwable e;
        if (cause instanceof ClosedChannelException) {
          e = new ConnectionClosedException(cause);
        } else {
          e = cause;
        }
        fail(responseHandler, e);
      }
      return set;
    }
  }

  private static class ClientStream extends Stream {

    private final Http2Request request;
    private final RequestPromise requestPromise;
    private final Http2Response response = new Http2Response();

    public ClientStream(final int id, final int localWindow, final Http2Request request,
                        final RequestPromise requestPromise) {
      super(id, request.content());
      this.request = request;
      this.requestPromise = requestPromise;
      this.localWindow = localWindow;
    }
  }

  private class WriteHandler extends ChannelDuplexHandler
      implements StreamWriter<ChannelHandlerContext, ClientStream> {

    private int streamId = 1;
    private boolean inActive;

    private int nextStreamId() {
      streamId += 2;
      return streamId;
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
      super.channelInactive(ctx);
      inActive = true;
    }

    @Override
    public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise)
        throws Exception {

      if (!(msg instanceof Http2Request)) {
        super.write(ctx, msg, promise);
        return;
      }

      if (inActive) {
        promise.tryFailure(new ConnectionClosedException());
        return;
      }

      final Http2Request request = (Http2Request) msg;
      final RequestPromise requestPromise = (RequestPromise) promise;

      // Already at max concurrent streams? Fail fast.
      if (streamController.streams() >= remoteMaxConcurrentStreams) {
        fail(requestPromise.responseHandler, new MaxConcurrentStreamsLimitReachedException());
        return;
      }

      // Generate ID and store response future in stream map to correlate with responses
      final int streamId = nextStreamId();
      final ClientStream stream = new ClientStream(streamId, localInitialStreamWindow, request, requestPromise);
      streamController.addStream(stream);
      flowController.start(stream);
    }

    @Override
    public void flush(final ChannelHandlerContext ctx) throws Exception {
      flowController.flush(ctx, this);
      ctx.flush();
    }

    private int estimateHeadersFrameSize(final Http2Headers headers) {
      int size = 0;
      for (final Map.Entry<CharSequence, CharSequence> header : headers) {
        size += Http2Header.size(header.getKey(), header.getValue());
      }
      return size;
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
      return buffer.readableBytes() - mark;
    }


    @Override
    public int estimateInitialHeadersFrameSize(final ChannelHandlerContext ctx, final ClientStream stream) {
      final Http2Request request = stream.request;
      return FRAME_HEADER_SIZE +
             Http2Header.size(METHOD.value(), request.method().asciiName()) +
             Http2Header.size(AUTHORITY.value(), request.authority()) +
             Http2Header.size(SCHEME.value(), request.scheme()) +
             Http2Header.size(PATH.value(), request.path()) +
             (request.hasHeaders() ? estimateHeadersFrameSize(request.headers()) : 0);
    }

    @Override
    public ByteBuf writeStart(final ChannelHandlerContext ctx, final int bufferSize) {
      return ctx.alloc().buffer(bufferSize);
    }

    @Override
    public void writeDataFrame(final ChannelHandlerContext ctx, final ByteBuf buf, final ClientStream stream,
                               final int payloadSize, final boolean endOfStream) {
      final int headerIndex = buf.writerIndex();
      final int flags = endOfStream ? END_STREAM : 0;
      assert buf.writableBytes() >= FRAME_HEADER_LENGTH;
      writeFrameHeader(buf, headerIndex, payloadSize, DATA, flags, stream.id);
      buf.writerIndex(headerIndex + FRAME_HEADER_LENGTH);
      // TODO: padding + fields
      buf.writeBytes(stream.data, payloadSize);
    }

    @Override
    public void writeInitialHeadersFrame(final ChannelHandlerContext ctx, final ByteBuf buf, final ClientStream stream,
                                         final boolean endOfStream) throws Http2Exception {
      final int headerIndex = buf.writerIndex();

      buf.ensureWritable(FRAME_HEADER_LENGTH);
      buf.writerIndex(headerIndex + FRAME_HEADER_LENGTH);
      final int size = encodeHeaders(stream.request, buf);

      if (size > remoteMaxConcurrentStreams) {
        // TODO: continuation frames
        throw new AssertionError();
      }

      final int flags = END_HEADERS | (endOfStream ? END_STREAM : 0);
      writeFrameHeader(buf, headerIndex, size, HEADERS, flags, stream.id);

      // TODO: padding + fields
    }

    @Override
    public void writeEnd(final ChannelHandlerContext ctx, final ByteBuf buf) {
      ctx.write(buf);
    }

    @Override
    public void streamEnd(final ClientStream stream) {
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {

    private InetSocketAddress address;
    private Listener listener;
    private SslContext sslContext;
    private EventLoop worker;

    private Integer maxConcurrentStreams;
    private Integer maxFrameSize;
    private Integer connectionWindowSize;
    private Integer initialStreamWindowSize;

    public Builder address(final InetSocketAddress address) {
      this.address = address;
      return this;
    }

    public Builder listener(final Listener listener) {
      this.listener = listener;
      return this;
    }

    public Builder worker(final EventLoop worker) {
      this.worker = worker;
      return this;
    }

    public Builder sslContext(final SslContext sslContext) {
      this.sslContext = sslContext;
      return this;
    }

    public Builder maxConcurrentStreams(final Integer maxConcurrentStreams) {
      this.maxConcurrentStreams = maxConcurrentStreams;
      return this;
    }

    public Builder maxFrameSize(final Integer maxFrameSize) {
      this.maxFrameSize = maxFrameSize;
      return this;
    }

    public Builder connectionWindowSize(final Integer connectionWindowSize) {
      this.connectionWindowSize = connectionWindowSize;
      return this;
    }

    public Builder initialStreamWindowSize(final Integer initialWindowSize) {
      this.initialStreamWindowSize = initialWindowSize;
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
