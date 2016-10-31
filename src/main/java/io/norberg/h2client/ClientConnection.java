package io.norberg.h2client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.channels.ClosedChannelException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.AsciiString;

import static io.netty.buffer.ByteBufUtil.writeAscii;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http2.Http2CodecUtil.WINDOW_UPDATE_FRAME_LENGTH;
import static io.netty.handler.codec.http2.Http2Error.PROTOCOL_ERROR;
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.AUTHORITY;
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.METHOD;
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.PATH;
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.SCHEME;
import static io.norberg.h2client.Http2WireFormat.CLIENT_PREFACE;
import static io.norberg.h2client.Http2WireFormat.FRAME_HEADER_SIZE;
import static io.norberg.h2client.Http2WireFormat.writeSettings;
import static java.util.Objects.requireNonNull;

class ClientConnection extends AbstractConnection<ClientConnection, ClientConnection.ClientStream> {

  private static final Logger log = LoggerFactory.getLogger(ClientConnection.class);
  public static final AsciiString OK_TEXT = OK.codeAsText();
  public static final AsciiString NOT_FOUND_TEXT = NOT_FOUND.codeAsText();
  public static final AsciiString BAD_REQUEST_TEXT = BAD_REQUEST.codeAsText();
  public static final AsciiString INTERNAL_SERVER_ERROR_TEXT = INTERNAL_SERVER_ERROR.codeAsText();

  private final Listener listener;

  private int streamId = 1;

  private ClientConnection(final Builder builder, final Channel channel) {
    super(builder, channel, log);
    this.listener = requireNonNull(builder.listener, "listener");
  }

  void send(final Http2Request request, final Http2ResponseHandler responseHandler) {
    final RequestPromise promise = new RequestPromise(channel(), responseHandler);
    send(request, promise);
  }

  private void dispatchResponse(final ClientStream stream) {
    deregisterStream(stream.id);
    Http2Response response = stream.response;
    Http2ResponseHandler responseHandler = stream.responseHandler;
    stream.responseHandler = null;
    stream.response = null;
    succeed(responseHandler, response);
//    response.release();
  }

  private int nextStreamId() {
    streamId += 2;
    return streamId;
  }

  @Override
  protected ClientConnection self() {
    return this;
  }

  @Override
  protected ChannelHandler handshakeHandler() {
    return new HandshakeHandler(channel());
  }

  @Override
  protected void connected() {
  }

  @Override
  protected void disconnected() {
  }

  @Override
  protected void peerSettingsChanged(final Http2Settings settings) {
    listener.peerSettingsChanged(this, settings);
    // TODO
  }

  @Override
  protected boolean handlesOutbound(final Object msg, final ChannelPromise promise) {
    return msg instanceof Http2Request;
  }

  @Override
  protected ClientStream outbound(final Object msg, final ChannelPromise promise) {
    final Http2Request request = (Http2Request) msg;
    final RequestPromise requestPromise = (RequestPromise) promise;

    // Already at max concurrent streams? Fail fast.
    if (activeStreams() >= remoteMaxConcurrentStreams()) {
      fail(requestPromise.responseHandler, new MaxConcurrentStreamsLimitReachedException());
      return null;
    }

    // Create new stream
    final int streamId = nextStreamId();
    final ClientStream
        stream =
        new ClientStream(streamId, localInitialStreamWindow(), request, requestPromise.responseHandler);

    registerStream(stream);

    return stream;
  }

  @Override
  protected void outboundEnd(final ClientStream stream) {
    stream.request.release();
    stream.request = null;
  }

  @Override
  protected int headersPayloadSize(final ClientStream stream) {
    final Http2Request request = stream.request;
    return FRAME_HEADER_SIZE +
           Http2Header.size(METHOD.value(), request.method().asciiName()) +
           Http2Header.size(AUTHORITY.value(), request.authority()) +
           Http2Header.size(SCHEME.value(), request.scheme()) +
           Http2Header.size(PATH.value(), request.path()) +
           Http2WireFormat.headersPayloadSize(request);
  }

  @Override
  protected void encodeHeaders(final ClientStream stream, final HpackEncoder headerEncoder,
                               final ByteBuf buf) throws Http2Exception {
    final Http2Request request = stream.request;
    headerEncoder.encodeRequest(buf,
                                request.method().asciiName(),
                                request.scheme(),
                                request.authority(),
                                request.path());
    for (int i = 0; i < request.headers(); i++) {
      headerEncoder.encodeHeader(buf, request.headerName(i), request.headerValue(i), false);
    }
  }

  @Override
  protected void startHeaders(final ClientStream stream,
                              final boolean endOfStream) {

  }

  @Override
  protected void readHeader(final ClientStream stream, final AsciiString name,
                            final AsciiString value) {
    stream.response.header(name, value);
  }

  @Override
  protected void readPseudoHeader(final ClientStream stream, final AsciiString name,
                                  final AsciiString value) throws Http2Exception {
    if (!name.equals(Http2Headers.PseudoHeaderName.STATUS.value())) {
      throw new Http2Exception(PROTOCOL_ERROR);
    }
    stream.response.status(responseStatus(value));
  }

  private static HttpResponseStatus responseStatus(final AsciiString value) {
    if (OK_TEXT.equals(value)) {
      return OK;
    } else if (NOT_FOUND_TEXT.equals(value)) {
      return NOT_FOUND;
    } else if (BAD_REQUEST_TEXT.equals(value)) {
      return BAD_REQUEST;
    } else if (INTERNAL_SERVER_ERROR_TEXT.equals(value)) {
      return INTERNAL_SERVER_ERROR;
    }
    return HttpResponseStatus.valueOf(value.parseInt());
  }

  @Override
  protected void endHeaders(final ClientStream stream, final boolean endOfStream) {
  }

  @Override
  protected void readData(final ClientStream stream, final ByteBuf data, final int padding,
                          final boolean endOfStream) {

    // TODO: allow user to provide codec that can be used to parse payload directly without copying it

    ByteBuf content = stream.response.content();
    if (content == null) {
      stream.response.content(Unpooled.copiedBuffer(data));
    } else {
      content.writeBytes(data);
    }
  }

  @Override
  protected ClientStream inbound(final int streamId) throws Http2Exception {
    return existingStream(streamId);
  }

  @Override
  protected void inboundEnd(final ClientStream stream) throws Http2Exception {
    dispatchResponse(stream);
  }

  protected static class ClientStream extends Stream {

    private Http2Request request;
    private Http2ResponseHandler responseHandler;
    private Http2Response response = new Http2Response();

    public ClientStream(final int id, final int localWindow, final Http2Request request,
                        final Http2ResponseHandler responseHandler) {
      super(id, request.content());
      this.localWindow = localWindow;
      this.request = request;
      this.responseHandler = responseHandler;
    }
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

  private void succeed(final Http2ResponseHandler responseHandler, final Http2Response response) {
    listener.responseReceived(ClientConnection.this, response);
    responseHandler.response(response);
  }

  private void fail(final Http2ResponseHandler responseHandler, final Throwable t) {
    listener.requestFailed(ClientConnection.this);
    responseHandler.failure(t);
  }

  static Builder builder() {
    return new Builder();
  }

  interface Listener {

    /**
     * Called when remote peer settings changed.
     */
    void peerSettingsChanged(ClientConnection connection, Http2Settings settings);

    void requestFailed(ClientConnection connection);

    void responseReceived(ClientConnection connection, Http2Response response);
  }

  static class Builder extends AbstractConnection.Builder<Builder> {

    private Listener listener;

    Listener listener() {
      return listener;
    }

    Builder listener(final Listener listener) {
      this.listener = listener;
      return this;
    }

    @Override
    protected Builder self() {
      return this;
    }

    public ClientConnection build(Channel channel) {
      return new ClientConnection(this, channel);
    }
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
          new HandshakeHandler(ch));
    }
  }

  private class HandshakeHandler extends ChannelInboundHandlerAdapter {

    private final Channel ch;

    public HandshakeHandler(final Channel ch) {
      this.ch = ch;
    }

    @Override
    public void channelActive(final ChannelHandlerContext ctx) throws Exception {
      super.channelActive(ctx);
      writePreface(ctx);
      handshakeDone();
      ch.pipeline().remove(this);
    }

    private void writePreface(final ChannelHandlerContext ctx) {
      final ByteBuf buf = ctx.alloc().buffer(
          CLIENT_PREFACE.length() + Http2WireFormat.settingsFrameLength(localSettings()) + WINDOW_UPDATE_FRAME_LENGTH);
      writeAscii(buf, CLIENT_PREFACE);
      writeSettings(buf, localSettings());
      ctx.writeAndFlush(buf);
    }
  }
}
