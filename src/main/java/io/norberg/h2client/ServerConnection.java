package io.norberg.h2client;

import com.spotify.netty4.util.BatchFlusher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Flags;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.ssl.SslContext;
import io.netty.util.AsciiString;

import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.netty.handler.codec.http2.Http2CodecUtil.DEFAULT_HEADER_TABLE_SIZE;
import static io.netty.handler.codec.http2.Http2CodecUtil.DEFAULT_MAX_FRAME_SIZE;
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
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.STATUS;
import static io.norberg.h2client.Http2Protocol.CLIENT_PREFACE;
import static io.norberg.h2client.Http2WireFormat.FRAME_HEADER_SIZE;
import static io.norberg.h2client.Http2WireFormat.writeFrameHeader;

class ServerConnection {

  private static final Logger log = LoggerFactory.getLogger(ServerConnection.class);

  private static final int MAX_CONTENT_LENGTH = Integer.MAX_VALUE;

  private final SslContext sslContext;
  private final RequestHandler requestHandler;

  private volatile int initialLocalWindow = Http2Protocol.DEFAULT_INITIAL_WINDOW_SIZE;

  private int localMaxFrameSize = DEFAULT_MAX_FRAME_SIZE;
  private int remoteMaxFrameSize = DEFAULT_MAX_FRAME_SIZE;
  private long remoteMaxConcurrentStreams = Long.MAX_VALUE;
  private long localMaxConcurrentStreams = 100;

  public ServerConnection(final SslContext sslContext, final RequestHandler requestHandler) {
    this.sslContext = sslContext;
    this.requestHandler = requestHandler;
  }

  public void initialize(final SocketChannel ch) {
    final Http2Settings settings = new Http2Settings();
    final StreamController<ServerStream> streamController = new StreamController<>();
    final FlowController<ChannelHandlerContext, ServerStream> flowController = new FlowController<>();
    ch.pipeline().addLast(
        sslContext.newHandler(ch.alloc()),
        new PrefaceHandler(settings),
        new WriteHandler(streamController, flowController),
        new ConnectionHandler(settings, ch, streamController, flowController),
        new ExceptionHandler());
  }

  private class WriteHandler extends ChannelDuplexHandler
      implements StreamWriter<ChannelHandlerContext, ServerStream> {

    private final HpackEncoder headerEncoder = new HpackEncoder(DEFAULT_HEADER_TABLE_SIZE);

    private final StreamController<ServerStream> streamController;
    private final FlowController<ChannelHandlerContext, ServerStream> flowController;

    private boolean inActive;

    public WriteHandler(final StreamController<ServerStream> streamController,
                        final FlowController<ChannelHandlerContext, ServerStream> flowController) {
      this.streamController = streamController;
      this.flowController = flowController;
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
      super.channelInactive(ctx);
      this.inActive = true;
    }

    @Override
    public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise)
        throws Exception {

      if (!(msg instanceof Http2Response)) {
        super.write(ctx, msg, promise);
        return;
      }

      if (inActive) {
        promise.tryFailure(new ConnectionClosedException());
        return;
      }

      final Http2Response response = (Http2Response) msg;
      final ResponsePromise responsePromise = (ResponsePromise) promise;
      // TODO: handle duplicate responses
      final ServerStream stream = responsePromise.stream;
      stream.response = response;
      stream.data = response.content();
      flowController.start(stream);
    }

    @Override
    public void flush(final ChannelHandlerContext ctx) throws Exception {
      flowController.flush(ctx, this);
      ctx.flush();
    }

    private int encodeHeaders(Http2Response response, ByteBuf buffer) throws HpackEncodingException {
      final int mark = buffer.readableBytes();
      headerEncoder.encodeResponse(buffer, response.status().codeAsText());
      if (response.hasHeaders()) {
        for (Map.Entry<CharSequence, CharSequence> header : response.headers()) {
          final AsciiString name = AsciiString.of(header.getKey());
          final AsciiString value = AsciiString.of(header.getValue());
          headerEncoder.encodeHeader(buffer, name, value, false);
        }
      }
      final int size = buffer.readableBytes() - mark;
      return size;
    }

    @Override
    public int estimateInitialHeadersFrameSize(final ChannelHandlerContext ctx,
                                               final ServerStream stream) throws Http2Exception {
      final Http2Response response = stream.response;
      return FRAME_HEADER_SIZE +
             Http2Header.size(STATUS.value(), response.status().codeAsText()) +
             (response.hasHeaders() ? estimateHeadersFrameSize(response.headers()) : 0);
    }

    private int estimateHeadersFrameSize(final Http2Headers headers) {
      int size = 0;
      for (final Map.Entry<CharSequence, CharSequence> header : headers) {
        size += Http2Header.size(header.getKey(), header.getValue());
      }
      return size;
    }

    @Override
    public ByteBuf writeStart(final ChannelHandlerContext ctx, final int bufferSize)
        throws Http2Exception {
      return ctx.alloc().buffer(bufferSize);
    }

    @Override
    public void writeDataFrame(final ChannelHandlerContext ctx, final ByteBuf buf,
                               final ServerStream stream, final int payloadSize, final boolean endOfStream)
        throws Http2Exception {
      final int headerIndex = buf.writerIndex();
      final int flags = endOfStream ? END_STREAM : 0;
      assert buf.writableBytes() >= FRAME_HEADER_LENGTH;
      writeFrameHeader(buf, headerIndex, payloadSize, DATA, flags, stream.id);
      buf.writerIndex(headerIndex + FRAME_HEADER_LENGTH);
      // TODO: padding + fields
      buf.writeBytes(stream.data, payloadSize);
    }

    @Override
    public void writeInitialHeadersFrame(final ChannelHandlerContext ctx, final ByteBuf buf,
                                         final ServerStream stream, final boolean endOfStream) throws Http2Exception {
      final int headerIndex = buf.writerIndex();

      buf.ensureWritable(FRAME_HEADER_LENGTH);
      buf.writerIndex(headerIndex + FRAME_HEADER_LENGTH);
      final int size = encodeHeaders(stream.response, buf);

      if (size > remoteMaxFrameSize) {
        // TODO: continuation frames
        throw new AssertionError();
      }

      final int flags = END_HEADERS | (endOfStream ? END_STREAM : 0);
      writeFrameHeader(buf, headerIndex, size, HEADERS, flags, stream.id);

      // TODO: padding + fields
    }

    @Override
    public void writeEnd(final ChannelHandlerContext ctx, final ByteBuf buf) throws Http2Exception {
      ctx.write(buf);
    }

    @Override
    public void streamEnd(final ServerStream stream) {
      streamController.removeStream(stream.id);
    }
  }

  private class ExceptionHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
      log.error("caught exception, closing connection", cause);
      ctx.close();
    }
  }

  private class ConnectionHandler extends ByteToMessageDecoder implements Http2FrameListener, ResponseChannel {

    private final Http2FrameReader reader;
    private final Http2Settings settings;

    private final BatchFlusher flusher;

    private final StreamController<ServerStream> streamController;
    private final FlowController<ChannelHandlerContext, ServerStream> flowController;

    private ChannelHandlerContext ctx;

    private int localWindowUpdateThreshold = initialLocalWindow / 2;
    private int localWindow = initialLocalWindow;

    /**
     * Stream that headers are currently being read for.
     */
    private ServerStream stream;

    public ConnectionHandler(final Http2Settings settings, final Channel channel,
                             final StreamController<ServerStream> streamController,
                             final FlowController<ChannelHandlerContext, ServerStream> flowController) {
      this.streamController = streamController;
      this.reader = new Http2FrameReader(new HpackDecoder(DEFAULT_HEADER_TABLE_SIZE), this);
      this.settings = settings;
      this.flusher = new BatchFlusher(channel);
      this.flowController = flowController;
    }

    @Override
    public void channelRegistered(final ChannelHandlerContext ctx) throws Exception {
      this.ctx = ctx;
    }

    @Override
    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
      super.channelActive(ctx);

      // Update channel window size
      final int sizeIncrement = initialLocalWindow - DEFAULT_WINDOW_SIZE;
      if (sizeIncrement > 0) {
        final ByteBuf buf = ctx.alloc().buffer(WINDOW_UPDATE_FRAME_LENGTH);
        writeWindowUpdate(buf, 0, sizeIncrement);
        ctx.write(buf);
        flusher.flush();
      }
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
      final ServerStream stream = streamController.existingStream(streamId);
      final int length = data.readableBytes();
      stream.localWindow -= length;
      localWindow -= length;
      final Http2Request request = stream.request;
      ByteBuf content = request.content();
      if (content == null) {
        // TODO: use pooled buffer or slice?
        content = Unpooled.buffer(length);
        request.content(content);
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
      stream = newOrExistingStream(streamId);
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
      stream = newOrExistingStream(streamId);
    }

    @Override
    public void onHeaderRead(final Http2Header header) throws Http2Exception {
      assert stream != null;
      final AsciiString name = header.name();
      final AsciiString value = header.value();
      if (name.byteAt(0) == ':') {
        readPseudoHeader(name, value);
      } else {
        stream.request.header(name, value);
      }
    }

    private void readPseudoHeader(final AsciiString name, final AsciiString value) throws Http2Exception {
      assert stream != null;
      if (name.length() < 5) {
        throw new IllegalArgumentException();
      }
      final byte b1 = name.byteAt(1);
      switch (b1) {
        case 'm': {
          if (!name.equals(METHOD.value())) {
            throw new Http2Exception(PROTOCOL_ERROR);
          }
          stream.request.method(HttpMethod.valueOf(value.toString()));
          return;
        }
        case 's': {
          if (!name.equals(SCHEME.value())) {
            throw new Http2Exception(PROTOCOL_ERROR);
          }
          stream.request.scheme(value);
          return;
        }
        case 'a': {
          if (!name.equals(AUTHORITY.value())) {
            throw new Http2Exception(PROTOCOL_ERROR);
          }
          stream.request.authority(value);
          return;
        }
        case 'p': {
          if (!name.equals(PATH.value())) {
            throw new Http2Exception(PROTOCOL_ERROR);
          }
          stream.request.path(value);
          return;
        }
        default:
          throw new Http2Exception(PROTOCOL_ERROR);
      }
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
      final ServerStream stream = streamController.removeStream(streamId);
      if (stream == null) {
        return;
      }
      flowController.stop(stream);
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
        remoteMaxConcurrentStreams = settings.maxConcurrentStreams();
      }
      if (settings.initialWindowSize() != null) {
        flowController.remoteInitialStreamWindowSizeUpdate(settings.initialWindowSize());
        flusher.flush();
      }
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
      if (log.isDebugEnabled()) {
        log.debug("got goaway, closing connection");
      }
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
        final ServerStream stream = streamController.existingStream(streamId);
        flowController.remoteStreamWindowUpdate(stream, windowSizeIncrement);
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

    private ServerStream newOrExistingStream(final int id) {
      ServerStream stream = streamController.stream(id);
      if (stream == null) {
        stream = new ServerStream(id, this, initialLocalWindow);
        streamController.addStream(stream);
      }
      return stream;
    }

    private void maybeDispatch(final ChannelHandlerContext ctx, final boolean endOfStream,
                               final ServerStream stream) {
      if (!endOfStream) {
        return;
      }

      // Hand off request to request handler
      try {
        requestHandler.handleRequest(stream, stream.request);
      } catch (Exception e) {
        log.error("Request handler threw exception", e);
        stream.fail();
      }
    }

    @Override
    public void sendResponse(final Http2Response response, final ServerStream stream) {
      ctx.write(response, new ResponsePromise(ctx.channel(), stream));
      flusher.flush();
    }
  }

  private class PrefaceHandler extends ByteToMessageDecoder {

    private int prefaceIndex;

    private final Http2Settings settings;

    private PrefaceHandler(final Http2Settings settings) {
      this.settings = settings;
    }

    @Override
    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
      writeSettings(ctx);
      ctx.flush();
      super.channelActive(ctx);
    }

    private void writeSettings(final ChannelHandlerContext ctx) {
      final Http2Settings settings = new Http2Settings();
      settings.initialWindowSize(initialLocalWindow);
      settings.maxConcurrentStreams(localMaxConcurrentStreams);
      settings.maxFrameSize(localMaxFrameSize);
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
      final int prefaceRemaining = CLIENT_PREFACE.length() - prefaceIndex;
      assert prefaceRemaining > 0;
      final int n = Math.min(in.readableBytes(), prefaceRemaining);
      for (int i = 0; i < n; i++, prefaceIndex++) {
        if (in.readByte() != CLIENT_PREFACE.byteAt(prefaceIndex)) {
          throw new Http2Exception(PROTOCOL_ERROR, "bad preface");
        }
      }
      if (prefaceIndex == CLIENT_PREFACE.length()) {
        ctx.pipeline().remove(this);
      }
    }
  }

  private static class ServerStream extends Stream implements Http2RequestContext {

    private final Http2Request request = new Http2Request();
    private final ResponseChannel channel;
    private Http2Response response;

    private int localWindow;

    public ServerStream(final int id, final ResponseChannel channel, final int localWindow) {
      super(id);
      this.channel = channel;
      this.localWindow = localWindow;
    }

    public void respond(final Http2Response response) {
      channel.sendResponse(response, this);
    }

    public void fail() {
      // Return 500 for request handler errors
      respond(new Http2Response(INTERNAL_SERVER_ERROR));
    }
  }

  private interface ResponseChannel {

    void sendResponse(Http2Response response, ServerStream streamId);
  }

  public static class ResponsePromise extends DefaultChannelPromise {

    final ServerStream stream;

    public ResponsePromise(final Channel channel, final ServerStream stream) {
      super(channel);
      this.stream = stream;
    }
  }
}
