package io.norberg.h2client;

import com.spotify.netty.util.BatchFlusher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.List;
import java.util.Optional;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Flags;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.AsciiString;

import static io.netty.buffer.ByteBufUtil.writeAscii;
import static io.netty.handler.codec.http2.Http2CodecUtil.DEFAULT_HEADER_TABLE_SIZE;
import static io.netty.handler.codec.http2.Http2CodecUtil.DEFAULT_WINDOW_SIZE;
import static io.netty.handler.codec.http2.Http2CodecUtil.FRAME_HEADER_LENGTH;
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
import static io.norberg.h2client.Http2Protocol.DEFAULT_INITIAL_WINDOW_SIZE;
import static io.norberg.h2client.Http2WireFormat.CLIENT_PREFACE;
import static io.norberg.h2client.Http2WireFormat.FRAME_HEADER_SIZE;
import static io.norberg.h2client.Http2WireFormat.writeFrameHeader;
import static io.norberg.h2client.Http2WireFormat.writeSettings;
import static io.norberg.h2client.Http2WireFormat.writeWindowUpdate;
import static java.lang.Integer.max;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

abstract class AbstractConnection<STREAM extends Stream> {

  private static final Logger log = LoggerFactory.getLogger(AbstractConnection.class);

  private final HpackEncoder headerEncoder = new HpackEncoder(DEFAULT_HEADER_TABLE_SIZE);

  private final StreamController<STREAM> streamController = new StreamController<>();
  private final FlowController<ChannelHandlerContext, STREAM> flowController = new FlowController<>();

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

  protected AbstractConnection(final Builder builder) {
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

    private final SslContext sslCtx;

    public Initializer(SslContext sslCtx) {
      this.sslCtx = requireNonNull(sslCtx, "sslCtx");
    }

    @Override
    public void initChannel(SocketChannel ch) throws Exception {
      final SslHandler sslHandler = sslCtx.newHandler(ch.alloc());

      // XXX: Discard read bytes well before consolidating
      // https://github.com/netty/netty/commit/c8a941d01e85148c21cc01bae80764bc134b1fdd
      sslHandler.setDiscardAfterReads(7);

      ch.pipeline().addLast(
          sslHandler,
          new ClientHandshakeHandler(ch));
    }
  }

  private class ExceptionHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
      log.error("caught exception, closing connection", cause);
      ctx.close();
    }
  }

  private class InboundHandler extends ByteToMessageDecoder implements Http2FrameListener {

    private final Http2FrameReader reader;

    // Stream currently being read
    private STREAM stream;

    InboundHandler() {
      this.reader = new Http2FrameReader(new HpackDecoder(DEFAULT_HEADER_TABLE_SIZE), this);
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
      super.channelInactive(ctx);
      AbstractConnection.this.disconnected();
      connected();
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
      final STREAM stream = streamController.existingStream(streamId);
      final int length = data.readableBytes();

      readData(stream, data, padding, endOfStream);

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

    @Override
    public void onHeadersRead(final ChannelHandlerContext ctx, final int streamId, final Http2Headers headers,
                              final int padding, final boolean endOfStream) throws Http2Exception {
      assert headers == null;
      if (log.isDebugEnabled()) {
        log.debug("got headers: streamId={}, padding={}, endOfStream={}",
                  streamId, padding, endOfStream);
      }
      this.stream = streamController.existingStream(streamId);
      startHeaders(stream, endOfStream);
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
      startHeaders(stream, endOfStream);
    }

    @Override
    public void onHeaderRead(final Http2Header header) throws Http2Exception {
      assert stream != null;
      final AsciiString name = header.name();
      final AsciiString value = header.value();
      if (name.byteAt(0) == ':') {
        readPseudoHeader(stream, name, value);
      } else {
        readHeader(stream, name, value);
      }
    }

    @Override
    public void onHeadersEnd(final ChannelHandlerContext ctx, final int streamId, final boolean endOfStream)
        throws Http2Exception {
      assert stream != null;
      endHeaders(stream, endOfStream);
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

      listener.peerSettingsChanged(AbstractConnection.this, settings);

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
        final STREAM stream = streamController.existingStream(streamId);

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
  }

  private void succeed(final Http2ResponseHandler responseHandler, final Http2Response response) {
    listener.responseReceived(AbstractConnection.this, response);
    responseHandler.response(response);
  }

  private void fail(final Http2ResponseHandler responseHandler, final Throwable t) {
    listener.requestFailed(AbstractConnection.this);
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

  private class OutboundHandler extends ChannelDuplexHandler
      implements StreamWriter<ChannelHandlerContext, STREAM> {

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

      if (inActive) {
        promise.tryFailure(new ConnectionClosedException());
        return;
      }

      if (!handlesOutbound(msg, promise)) {
        ctx.write(msg, promise);
        return;
      }

      final STREAM stream = outbound(msg, promise);
      if (stream == null) {
        return;
      }

      flowController.start(stream);
    }

    @Override
    public void flush(final ChannelHandlerContext ctx) throws Exception {
      flowController.flush(ctx, this);
      ctx.flush();
    }

    @Override
    public int estimateInitialHeadersFrameSize(final ChannelHandlerContext ctx, final STREAM stream) {
      return FRAME_HEADER_SIZE + headersPayloadSize(stream);
    }

    @Override
    public ByteBuf writeStart(final ChannelHandlerContext ctx, final int bufferSize) {
      return ctx.alloc().buffer(bufferSize);
    }

    @Override
    public void writeDataFrame(final ChannelHandlerContext ctx, final ByteBuf buf, final STREAM stream,
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
    public void writeInitialHeadersFrame(final ChannelHandlerContext ctx, final ByteBuf buf, final STREAM stream,
                                         final boolean endOfStream) throws Http2Exception {
      final int headerIndex = buf.writerIndex();

      assert buf.writableBytes() >= FRAME_HEADER_LENGTH;
      buf.writerIndex(headerIndex + FRAME_HEADER_LENGTH);
      final int size = encodeHeaders(stream, headerEncoder, buf);

      if (size > remoteMaxFrameSize) {
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
    public void streamEnd(final STREAM stream) {
    }
  }

  protected abstract int encodeHeaders(final STREAM stream, final HpackEncoder headerEncoder, final ByteBuf buf)
      throws Http2Exception;

  protected abstract int headersPayloadSize(final STREAM stream);

  abstract static class Builder<BUILDER> {

    private InetSocketAddress address;
    private Listener listener;
    private SslContext sslContext;
    private EventLoop worker;

    private Integer maxConcurrentStreams;
    private Integer maxFrameSize;
    private Integer connectionWindowSize;
    private Integer initialStreamWindowSize;

    BUILDER address(final InetSocketAddress address) {
      this.address = address;
      return self();
    }

    BUILDER listener(final Listener listener) {
      this.listener = listener;
      return self();
    }

    BUILDER worker(final EventLoop worker) {
      this.worker = worker;
      return self();
    }

    BUILDER sslContext(final SslContext sslContext) {
      this.sslContext = sslContext;
      return self();
    }

    BUILDER maxConcurrentStreams(final Integer maxConcurrentStreams) {
      this.maxConcurrentStreams = maxConcurrentStreams;
      return self();
    }

    BUILDER maxFrameSize(final Integer maxFrameSize) {
      this.maxFrameSize = maxFrameSize;
      return self();
    }

    BUILDER connectionWindowSize(final Integer connectionWindowSize) {
      this.connectionWindowSize = connectionWindowSize;
      return self();
    }

    BUILDER initialStreamWindowSize(final Integer initialWindowSize) {
      this.initialStreamWindowSize = initialWindowSize;
      return self();
    }

    protected abstract BUILDER self();
  }

  interface Listener {

    /**
     * Called when remote peer settings changed.
     */
    void peerSettingsChanged(AbstractConnection connection, Http2Settings settings);

    void requestFailed(AbstractConnection connection);

    void responseReceived(AbstractConnection connection, Http2Response response);
  }

  private class ClientHandshakeHandler extends ChannelInboundHandlerAdapter {

    private final Channel ch;

    public ClientHandshakeHandler(final Channel ch) {
      this.ch = ch;
    }

    @Override
    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
      super.channelActive(ctx);
      writePreface(ctx);
      ch.pipeline().remove(this);
      start();
    }

    private void writePreface(final ChannelHandlerContext ctx) {
      final ByteBuf buf = ctx.alloc().buffer(
          CLIENT_PREFACE.length() + Http2WireFormat.settingsFrameLength(localSettings) + WINDOW_UPDATE_FRAME_LENGTH);

      writeAscii(buf, CLIENT_PREFACE);
      writeSettings(buf, localSettings);

      // Update connection window size
      final int sizeIncrement = localMaxConnectionWindow - DEFAULT_WINDOW_SIZE;
      if (sizeIncrement > 0) {
        writeWindowUpdate(buf, 0, sizeIncrement);
      }

      ctx.writeAndFlush(buf);
    }
  }

  private class ServerHandshakeHandler extends ByteToMessageDecoder {

    private final Channel ch;
    private final Http2Settings settings;

    private int prefaceIndex;

    public ServerHandshakeHandler(final Channel ch, final Http2Settings settings) {
      this.ch = ch;
      this.settings = settings;
    }

    @Override
    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
      writeSettings(ctx);
      ctx.flush();
      super.channelActive(ctx);
    }

    private void writeSettings(final ChannelHandlerContext ctx) {
      final int length = SETTING_ENTRY_LENGTH * settings.size();
      final ByteBuf buf = ctx.alloc().buffer(FRAME_HEADER_LENGTH + length);
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
    protected void decode(final ChannelHandlerContext ctx, final ByteBuf in, final List<Object> out)
        throws Exception {
      final int prefaceRemaining = Http2Protocol.CLIENT_PREFACE.length() - prefaceIndex;
      assert prefaceRemaining > 0;
      final int n = Math.min(in.readableBytes(), prefaceRemaining);
      for (int i = 0; i < n; i++, prefaceIndex++) {
        if (in.readByte() != Http2Protocol.CLIENT_PREFACE.byteAt(prefaceIndex)) {
          throw new Http2Exception(PROTOCOL_ERROR, "bad preface");
        }
      }
      if (prefaceIndex == Http2Protocol.CLIENT_PREFACE.length()) {
        ctx.pipeline().remove(this);
        start();
      }
    }
  }

  void send(final Object request, final ChannelPromise promise) {
    channel.write(request, promise);
    flusher.flush();
  }

  protected Channel channel() {
    return channel;
  }

  protected void start() {
    channel.pipeline().addLast(new InboundHandler(),
                               new OutboundHandler(),
                               new ExceptionHandler());

    connected();
  }

  protected void registerStream(STREAM stream) {
    streamController.addStream(stream);
  }

  protected STREAM deregisterStream(int id) {
    return streamController.removeStream(id);
  }



  protected int remoteMaxFrameSize() {
    return remoteMaxFrameSize;
  }

  protected int remoteMaxConcurrentStreams() {
    return remoteMaxConcurrentStreams;
  }

  protected int remoteMaxHeaderListSize() {
    return remoteMaxHeaderListSize;
  }

  protected int localInitialStreamWindow() {
    return localInitialStreamWindow;
  }

  protected int localMaxConnectionWindow() {
    return localMaxConnectionWindow;
  }

  protected int activeStreams() {
    return streamController.streams();
  }

  protected abstract void connected();

  protected abstract void disconnected();

  protected abstract void readData(final STREAM stream, final ByteBuf data, final int padding,
                                   final boolean endOfStream)
      throws Http2Exception;

  protected abstract boolean handlesOutbound(final Object msg, final ChannelPromise promise);

  protected abstract STREAM outbound(final Object msg, final ChannelPromise promise)
      throws Http2Exception;

  protected abstract void endHeaders(final STREAM stream, final boolean endOfStream)
      throws Http2Exception;

  protected abstract void startHeaders(final STREAM stream, final boolean endOfStream)
      throws Http2Exception;

  protected abstract void readHeader(final STREAM stream, final AsciiString name, final AsciiString value)
      throws Http2Exception;

  protected abstract void readPseudoHeader(final STREAM stream, final AsciiString name, final AsciiString value)
      throws Http2Exception;
}
