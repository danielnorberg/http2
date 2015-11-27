package io.norberg.h2client;

import com.spotify.netty4.util.BatchFlusher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Flags;
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.ssl.SslContext;
import io.netty.util.AsciiString;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.concurrent.GlobalEventExecutor;

import static io.netty.handler.codec.http2.Http2CodecUtil.DEFAULT_HEADER_TABLE_SIZE;
import static io.netty.handler.codec.http2.Http2CodecUtil.DEFAULT_MAX_FRAME_SIZE;
import static io.netty.handler.codec.http2.Http2CodecUtil.FRAME_HEADER_LENGTH;
import static io.netty.handler.codec.http2.Http2Error.PROTOCOL_ERROR;
import static io.netty.handler.codec.http2.Http2Flags.END_HEADERS;
import static io.netty.handler.codec.http2.Http2Flags.END_STREAM;
import static io.netty.handler.codec.http2.Http2FrameTypes.DATA;
import static io.netty.handler.codec.http2.Http2FrameTypes.HEADERS;
import static io.netty.handler.codec.http2.Http2FrameTypes.SETTINGS;
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.AUTHORITY;
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.METHOD;
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.PATH;
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.SCHEME;
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.STATUS;
import static io.netty.handler.logging.LogLevel.TRACE;
import static io.norberg.h2client.Http2WireFormat.writeFrameHeader;
import static io.norberg.h2client.Util.completableFuture;

public class Http2Server {

  private static final Logger log = LoggerFactory.getLogger(Http2Server.class);

  private static final int MAX_CONTENT_LENGTH = Integer.MAX_VALUE;

  private final ChannelGroup channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE, true);

  private final ChannelFuture bindFuture;
  private final Channel channel;
  private final RequestHandler requestHandler;

  private final CompletableFuture<Void> closeFuture = new CompletableFuture<>();

  private int maxFrameSize = DEFAULT_MAX_FRAME_SIZE;
  private long maxConcurrentStreams;

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


  private class Initializer extends ChannelInitializer<SocketChannel> {

    private final Http2FrameLogger logger = new Http2FrameLogger(TRACE, Initializer.class);

    private final SslContext sslCtx;

    public Initializer(final SslContext sslCtx) {
      this.sslCtx = sslCtx;
    }

    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
      channels.add(ch);
      final Http2Settings settings = new Http2Settings();
      ch.pipeline().addLast(
          sslCtx.newHandler(ch.alloc()),
          new PrefaceHandler(settings),
          new WriteHandler(),
          new ConnectionHandler(settings, ch),
          new ExceptionHandler());
    }
  }

  private class WriteHandler extends ChannelOutboundHandlerAdapter {

    private final List<Http2Response> responses = new ArrayList<>();
    private final List<ResponsePromise> promises = new ArrayList<>();

    private final HpackEncoder headerEncoder = new HpackEncoder(DEFAULT_HEADER_TABLE_SIZE);

    @Override
    public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise)
        throws Exception {
      if (!(msg instanceof Http2Response)) {
        super.write(ctx, msg, promise);
        return;
      }
      responses.add((Http2Response) msg);
      promises.add((ResponsePromise) promise);
    }

    @Override
    public void flush(final ChannelHandlerContext ctx) throws Exception {
      final int n = responses.size();

      final ByteBuf buf = ctx.alloc().buffer(estimateSize(responses));

      for (int i = 0; i < n; i++) {
        final Http2Response response = responses.get(i);
        final ResponsePromise promise = promises.get(i);

        final boolean hasContent = response.hasContent();
        try {
          writeHeaders(buf, promise.streamId, response, !hasContent);
        } catch (HpackEncodingException e) {
          ctx.fireExceptionCaught(e);
          return;
        }
        if (hasContent) {
          writeData(buf, promise.streamId, response.content(), true);
        }
      }

      responses.clear();
      promises.clear();

      final AggregatePromise aggregatePromise = new AggregatePromise(ctx.channel(), promises);
      ctx.writeAndFlush(buf, aggregatePromise);
    }

    private int estimateSize(final List<Http2Response> responses) {
      int size = 0;
      for (int i = 0; i < responses.size(); i++) {
        final Http2Response request = responses.get(i);
        size += estimateSize(request);
      }
      return size;
    }

    private static final int FRAME_HEADER_SIZE = 9;

    private int estimateSize(final Http2Response response) {
      final int headersFrameSize =
          FRAME_HEADER_SIZE +
          Http2Header.size(STATUS.value(), response.status().codeAsText()) +
          (response.hasHeaders() ? estimateSize(response) : 0);
      final int dataFrameSize =
          FRAME_HEADER_SIZE +
          (response.hasContent() ? response.content().readableBytes() : 0);
      return headersFrameSize + dataFrameSize;
    }

    private void writeHeaders(final ByteBuf buf, int streamId, Http2Response response, boolean endStream)
        throws HpackEncodingException {
      final int headerIndex = buf.writerIndex();

      buf.ensureWritable(FRAME_HEADER_LENGTH);
      buf.writerIndex(headerIndex + FRAME_HEADER_LENGTH);
      final int size = encodeHeaders(response, buf);

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
  }

  private class ExceptionHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) throws Exception {
      log.error("caught exception, closing connection", cause);
      ctx.close();
    }
  }

  private class ConnectionHandler extends ByteToMessageDecoder implements Http2FrameListener, ResponseChannel {
    private final IntObjectHashMap<Http2Request> requests = new IntObjectHashMap<>();

    private final Http2FrameReader reader;
    private final Http2Settings settings;

    private final BatchFlusher flusher;

    private Http2Request request;
    private ChannelHandlerContext ctx;

    public ConnectionHandler(final Http2Settings settings, final Channel channel) {
      this.reader = new Http2FrameReader(new HpackDecoder(DEFAULT_HEADER_TABLE_SIZE), this);
      this.settings = settings;
      this.flusher = new BatchFlusher(channel);
    }

    @Override
    public void channelRegistered(final ChannelHandlerContext ctx) throws Exception {
      this.ctx = ctx;
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
      final Http2Request request = existingRequest(streamId);
      final int n = data.readableBytes();
      ByteBuf content = request.content();
      if (content == null) {
        content = ctx.alloc().buffer(data.readableBytes());
        request.content(content);
      }
      content.writeBytes(data);
      maybeDispatch(ctx, streamId, endOfStream, request);
      return n + padding;
    }

    @Override
    public void onHeadersRead(final ChannelHandlerContext ctx, final int streamId, final Http2Headers headers,
                              final int padding, final boolean endOfStream) throws Http2Exception {
      assert headers == null;
      if (log.isDebugEnabled()) {
        log.debug("got headers: streamId={}, padding={}, endOfStream={}",
                  streamId, padding, endOfStream);
      }
      request = newOrExistingRequest(streamId);
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
      request = newOrExistingRequest(streamId);
    }

    @Override
    public void onHeaderRead(final Http2Header header) throws Http2Exception {
      assert request != null;
      final AsciiString name = header.name();
      final AsciiString value = header.value();
      if (name.byteAt(0) == ':') {
        readPseudoHeader(name, value);
      } else {
        request.header(name, value);
      }
    }

    private void readPseudoHeader(final AsciiString name, final AsciiString value) throws Http2Exception {
      assert request != null;
      if (name.length() < 5) {
        throw new IllegalArgumentException();
      }
      final byte b1 = name.byteAt(1);
      switch (b1) {
        case 'm': {
          if (!name.equals(METHOD.value())) {
            throw new Http2Exception(PROTOCOL_ERROR);
          }
          request.method(HttpMethod.valueOf(value.toString()));
          return;
        }
        case 's': {
          if (!name.equals(SCHEME.value())) {
            throw new Http2Exception(PROTOCOL_ERROR);
          }
          request.scheme(value);
          return;
        }
        case 'a': {
          if (!name.equals(AUTHORITY.value())) {
            throw new Http2Exception(PROTOCOL_ERROR);
          }
          request.authority(value);
          return;
        }
        case 'p': {
          if (!name.equals(PATH.value())) {
            throw new Http2Exception(PROTOCOL_ERROR);
          }
          request.path(value);
          return;
        }
        default:
          throw new Http2Exception(PROTOCOL_ERROR);
      }
    }

    @Override
    public void onHeadersEnd(final ChannelHandlerContext ctx, final int streamId, final boolean endOfStream)
        throws Http2Exception {
      assert request != null;
      maybeDispatch(ctx, streamId, endOfStream, request);
      request = null;
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
        maxFrameSize = settings.maxFrameSize();
      }
      if (settings.maxConcurrentStreams() != null) {
        maxConcurrentStreams = settings.maxConcurrentStreams();
      }
    }

    @Override
    public void onPingRead(final ChannelHandlerContext ctx, final ByteBuf data) throws Http2Exception {
      if (log.isDebugEnabled()) {
        log.debug("got ping");
      }
      // TODO: ack
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
    }

    @Override
    public void onUnknownFrame(final ChannelHandlerContext ctx, final byte frameType, final int streamId,
                               final Http2Flags flags, final ByteBuf payload)
        throws Http2Exception {
      if (log.isDebugEnabled()) {
        log.debug("got unknown frame: {} {} {} {}", frameType, streamId, flags, payload);
      }
    }

    private Http2Request existingRequest(final int streamId) throws Http2Exception {
      final Http2Request request = requests.get(streamId);
      if (request == null) {
        throw Util.connectionError(PROTOCOL_ERROR, "Data Frame recieved for unknown stream id %d", streamId);
      }
      return request;
    }

    private Http2Request newOrExistingRequest(final int streamId) {
      Http2Request request = requests.get(streamId);
      if (request == null) {
        request = new Http2Request();
        requests.put(streamId, request);
      }
      return request;
    }

    private void maybeDispatch(final ChannelHandlerContext ctx, final int streamId, final boolean endOfStream,
                               final Http2Request request) {
      if (!endOfStream) {
        return;
      }

      // TODO: only add request if not only a single headers frame
      requests.remove(streamId);

      // Hand off request to request handler
      final Http2RequestContext context = new Http2RequestContext(this, streamId);
      dispatch(context, request);
    }

    private void dispatch(final Http2RequestContext context, final Http2Request request) {
      try {
        requestHandler.handleRequest(context, request);
      } catch (Exception e) {
        context.fail();
      }
    }

    @Override
    public void sendResponse(final Http2Response response, final int streamId) {
      ctx.write(response, new ResponsePromise(ctx.channel(), streamId));
      flusher.flush();
    }
  }

  private static class PrefaceHandler extends ByteToMessageDecoder {

    private static final AsciiString CLIENT_PREFACE =
        AsciiString.of("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n");

    private int prefaceIndex;

    private final Http2Settings settings;

    private PrefaceHandler(final Http2Settings settings) {
      this.settings = settings;
    }

    @Override
    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
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


}
