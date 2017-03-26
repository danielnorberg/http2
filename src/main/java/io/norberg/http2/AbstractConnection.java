package io.norberg.http2;

import com.spotify.netty.util.BatchFlusher;

import org.slf4j.Logger;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Flags;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.AsciiString;
import io.netty.util.collection.IntObjectHashMap;

import static io.netty.handler.codec.http2.Http2CodecUtil.DEFAULT_HEADER_TABLE_SIZE;
import static io.netty.handler.codec.http2.Http2CodecUtil.DEFAULT_WINDOW_SIZE;
import static io.netty.handler.codec.http2.Http2CodecUtil.FRAME_HEADER_LENGTH;
import static io.netty.handler.codec.http2.Http2CodecUtil.PING_FRAME_PAYLOAD_LENGTH;
import static io.netty.handler.codec.http2.Http2CodecUtil.WINDOW_UPDATE_FRAME_LENGTH;
import static io.netty.handler.codec.http2.Http2Error.PROTOCOL_ERROR;
import static io.netty.handler.codec.http2.Http2Flags.ACK;
import static io.netty.handler.codec.http2.Http2Flags.END_STREAM;
import static io.netty.handler.codec.http2.Http2FrameTypes.DATA;
import static io.netty.handler.codec.http2.Http2FrameTypes.PING;
import static io.netty.handler.codec.http2.Http2FrameTypes.SETTINGS;
import static io.norberg.http2.Hpack.writeDynamicTableSizeUpdate;
import static io.norberg.http2.Http2Protocol.DEFAULT_INITIAL_WINDOW_SIZE;
import static io.norberg.http2.Http2WireFormat.FRAME_HEADER_SIZE;
import static io.norberg.http2.Http2WireFormat.writeFrameHeader;
import static io.norberg.http2.Http2WireFormat.writeWindowUpdate;
import static io.norberg.http2.Util.connectionError;
import static java.lang.Integer.max;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

abstract class AbstractConnection<CONNECTION extends AbstractConnection<CONNECTION, STREAM>, STREAM extends Http2Stream> {

  private final Logger log;

  private final HpackEncoder headerEncoder = new HpackEncoder(DEFAULT_HEADER_TABLE_SIZE);

  private final IntObjectHashMap<STREAM> streams = new IntObjectHashMap<>();
  private final FlowController<ChannelHandlerContext, STREAM> flowController = new FlowController<>();

  private final SslContext sslContext;
  private final Channel channel;
  private final BatchFlusher flusher;

  private final Http2Settings localSettings = new Http2Settings();

  private final CompletableFuture<CONNECTION> connectFuture = new CompletableFuture<>();
  private final CompletableFuture<CONNECTION> disconnectFuture = new CompletableFuture<>();

  private int remoteMaxFrameSize = Http2CodecUtil.DEFAULT_MAX_FRAME_SIZE;
  private int remoteMaxConcurrentStreams = Integer.MAX_VALUE;
  private long remoteMaxHeaderListSize = Long.MAX_VALUE;

  private final int localInitialStreamWindow;
  private final int localMaxConnectionWindow;
  private final int maxHeaderEncoderTableSize;

  private final int localConnectionWindowUpdateThreshold;
  private final int localStreamWindowUpdateThreshold;

  private int localConnectionWindow;

  // TODO: move this state into HpackEncoder
  private boolean headerTableSizeUpdatePending;

  AbstractConnection(final Builder<?> builder, final Channel channel, final Logger log) {
    this.localInitialStreamWindow = Optional.ofNullable(builder.initialStreamWindowSize)
        .orElse(DEFAULT_INITIAL_WINDOW_SIZE);
    this.localMaxConnectionWindow = Optional.ofNullable(builder.connectionWindowSize)
        .orElse(DEFAULT_INITIAL_WINDOW_SIZE);
    this.maxHeaderEncoderTableSize = Optional.ofNullable(builder.maxHeaderEncoderTableSize)
        .orElse(DEFAULT_HEADER_TABLE_SIZE);
    this.log = log;
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

    this.sslContext = requireNonNull(builder.sslContext, "sslContext");
    this.channel = requireNonNull(channel, "channel");
    this.flusher = BatchFlusher.of(channel, channel.eventLoop());

    connect();
  }

  private void connect() {
    final SslHandler sslHandler = sslContext().newHandler(channel().alloc());

    // XXX: Discard read bytes well before consolidating
    // https://github.com/netty/netty/commit/c8a941d01e85148c21cc01bae80764bc134b1fdd
    sslHandler.setDiscardAfterReads(7);

    channel().pipeline().addLast(
        sslHandler,
        handshakeHandler(),
        new ExceptionHandler());
  }

  CompletableFuture<CONNECTION> connectFuture() {
    return connectFuture;
  }

  CompletableFuture<CONNECTION> disconnectFuture() {
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

  private class ExceptionHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
      log.error("Connection caught exception, closing channel: {}", ctx.channel(), cause);
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
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
      super.handlerAdded(ctx);
      // Update connection window size
      final int sizeIncrement = localMaxConnectionWindow - DEFAULT_WINDOW_SIZE;
      if (sizeIncrement > 0) {
        final ByteBuf buf = ctx.alloc().buffer(WINDOW_UPDATE_FRAME_LENGTH);
        writeWindowUpdate(buf, 0, sizeIncrement);
        ctx.writeAndFlush(buf);
      }
    }

    @Override
    protected void handlerRemoved0(ChannelHandlerContext ctx) throws Exception {
      super.handlerRemoved0(ctx);
      reader.close();
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
      super.channelInactive(ctx);
      AbstractConnection.this.disconnected();
      disconnectFuture.complete(self());
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
      final STREAM stream = existingStream(streamId);
      final int length = data.readableBytes();

      readData(stream, data, padding, endOfStream);

      if (endOfStream) {
        inboundEnd(stream);
      }

      stream.localWindow -= length;
      localConnectionWindow -= length;

      // TODO: eagerly replenish windows even when idle?

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
    public void onHeadersRead(final ChannelHandlerContext ctx, final int streamId,
        final boolean endOfStream) throws Http2Exception {
      if (log.isDebugEnabled()) {
        log.debug("got headers: streamId={}, endOfStream={}",
                  streamId, endOfStream);
      }
      if (stream == null) {
        this.stream = inbound(streamId);
      }
      startHeaders(stream, endOfStream);
    }

    @Override
    public void onHeadersRead(final ChannelHandlerContext ctx, final int streamId,
        final int streamDependency, final short weight, final boolean exclusive,
        final boolean endOfStream)
        throws Http2Exception {
      if (log.isDebugEnabled()) {
        log.debug("got headers: streamId={}, streamDependency={}, weight={}, exclusive={}, "
                  + "endOfStream={}", streamId, streamDependency, weight, exclusive, endOfStream);
      }
      if (stream == null) {
        this.stream = inbound(streamId);
      }
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
      if (endOfStream) {
        inboundEnd(stream);
      }
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
        flowController.remoteInitialStreamWindowSizeUpdate(settings.initialWindowSize(), streams.values());
        flusher.flush();
      }
      if (settings.maxHeaderListSize() != null) {
        remoteMaxHeaderListSize = settings.maxHeaderListSize();
      }
      if (settings.headerTableSize() != null) {
        // We can freely choose a table size that's smaller than the signaled size
        final int headerTableSize = (int) Math.min(maxHeaderEncoderTableSize, settings.headerTableSize());
        headerTableSizeUpdatePending = true;
        headerEncoder.setMaxTableSize(headerTableSize);
      }

      // TODO: SETTINGS_ENABLE_PUSH

      peerSettingsChanged(settings);

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
        final STREAM stream = stream(streamId);

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

  protected final STREAM existingStream(final int streamId) throws Http2Exception {
    final STREAM stream = stream(streamId);
    if (stream == null) {
      throw connectionError(PROTOCOL_ERROR, "Unknown stream id: %d", streamId);
    }
    return stream;
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
      return FRAME_HEADER_SIZE + dynamicTableSizeUpdateSize() + headersPayloadSize(stream);
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
      int blockIndex = headerIndex + FRAME_HEADER_LENGTH;
      buf.writerIndex(blockIndex);

      // Signal header table size update in the beginning of the block, if necessary
      if (headerTableSizeUpdatePending) {
        headerTableSizeUpdatePending = false;
        writeDynamicTableSizeUpdate(buf, headerEncoder.maxTableSize());
      }

      encodeHeaders(stream, headerEncoder, buf);

      final int blockSize = buf.writerIndex() - blockIndex;

      final int writerIndex = HeaderFraming.frameHeaderBlock(buf, headerIndex, blockSize, remoteMaxFrameSize, endOfStream, stream.id);
      buf.writerIndex(writerIndex);

      // TODO: padding + fields
    }

    @Override
    public void writeEnd(final ChannelHandlerContext ctx, final ByteBuf buf) {
      ctx.write(buf);
    }

    @Override
    public void streamEnd(final STREAM stream) {
      outboundEnd(stream);
    }
  }

  private int dynamicTableSizeUpdateSize() {
    if (headerTableSizeUpdatePending) {
      return Hpack.dynamicTableSizeUpdateSize(headerEncoder.maxTableSize());
    } else {
      return 0;
    }
  }

  protected abstract void encodeHeaders(final STREAM stream, final HpackEncoder headerEncoder, final ByteBuf buf)
      throws Http2Exception;

  protected abstract int headersPayloadSize(final STREAM stream);

  abstract static class Builder<BUILDER extends Builder<BUILDER>> {

    private SslContext sslContext;
    private Integer maxConcurrentStreams;
    private Integer maxFrameSize;
    private Integer connectionWindowSize;
    private Integer maxHeaderEncoderTableSize;
    private Integer initialStreamWindowSize;

    SslContext sslContext() {
      return sslContext;
    }

    BUILDER sslContext(final SslContext sslContext) {
      this.sslContext = sslContext;
      return self();
    }

    Integer maxConcurrentStreams() {
      return maxConcurrentStreams;
    }

    BUILDER maxConcurrentStreams(final Integer maxConcurrentStreams) {
      this.maxConcurrentStreams = maxConcurrentStreams;
      return self();
    }

    Integer maxFrameSize() {
      return maxFrameSize;
    }

    BUILDER maxFrameSize(final Integer maxFrameSize) {
      this.maxFrameSize = maxFrameSize;
      return self();
    }

    Integer connectionWindowSize() {
      return connectionWindowSize;
    }

    BUILDER connectionWindowSize(final Integer connectionWindowSize) {
      this.connectionWindowSize = connectionWindowSize;
      return self();
    }

    Integer maxHeaderEncoderTableSize() {
      return maxHeaderEncoderTableSize;
    }

    BUILDER maxHeaderEncoderTableSize(final Integer maxHeaderTableSize) {
      this.maxHeaderEncoderTableSize = connectionWindowSize;
      return self();
    }

    Integer initialStreamWindowSize() {
      return initialStreamWindowSize;
    }

    BUILDER initialStreamWindowSize(final Integer initialWindowSize) {
      this.initialStreamWindowSize = initialWindowSize;
      return self();
    }

    protected abstract BUILDER self();
  }

  void send(final Object message, final ChannelPromise promise) {
    channel.write(message, promise);
    flusher.flush();
  }

  protected final SslContext sslContext() {
    return sslContext;
  }

  protected final Channel channel() {
    return channel;
  }

  protected final void handshakeDone() {
    // TODO: more robust pipeline setup
    channel.pipeline().remove(ExceptionHandler.class);
    channel.pipeline().addLast(new InboundHandler(),
                               new OutboundHandler(),
                               new ExceptionHandler());
    connected();
    connectFuture.complete(self());
  }

  protected final STREAM stream(int id) {
    return streams.get(id);
  }

  protected final void registerStream(STREAM stream) {
    streams.put(stream.id, stream);
  }

  protected final STREAM deregisterStream(int id) {
    return streams.remove(id);
  }

  protected final Http2Settings localSettings() {
    return localSettings;
  }

  protected final int remoteMaxFrameSize() {
    return remoteMaxFrameSize;
  }

  protected final int remoteMaxConcurrentStreams() {
    return remoteMaxConcurrentStreams;
  }

  protected final long remoteMaxHeaderListSize() {
    return remoteMaxHeaderListSize;
  }

  protected final int localInitialStreamWindow() {
    return localInitialStreamWindow;
  }

  protected final int localMaxConnectionWindow() {
    return localMaxConnectionWindow;
  }

  protected final int activeStreams() {
    return streams.size();
  }

  protected abstract CONNECTION self();

  protected abstract ChannelHandler handshakeHandler();

  protected abstract void connected();

  protected abstract void disconnected();

  protected abstract void peerSettingsChanged(final Http2Settings settings);

  protected abstract void readData(final STREAM stream, final ByteBuf data, final int padding,
                                   final boolean endOfStream)
      throws Http2Exception;

  protected abstract STREAM inbound(final int streamId) throws Http2Exception;

  protected abstract void inboundEnd(final STREAM stream) throws Http2Exception;

  protected abstract boolean handlesOutbound(final Object msg, final ChannelPromise promise);

  protected abstract STREAM outbound(final Object msg, final ChannelPromise promise)
      throws Http2Exception;

  protected abstract void outboundEnd(final STREAM stream);

  protected abstract void endHeaders(final STREAM stream, final boolean endOfStream)
      throws Http2Exception;

  protected abstract void startHeaders(final STREAM stream, final boolean endOfStream)
      throws Http2Exception;

  protected abstract void readHeader(final STREAM stream, final AsciiString name, final AsciiString value)
      throws Http2Exception;

  protected abstract void readPseudoHeader(final STREAM stream, final AsciiString name, final AsciiString value)
      throws Http2Exception;
}
