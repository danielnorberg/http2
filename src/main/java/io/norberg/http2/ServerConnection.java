package io.norberg.http2;

import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.norberg.http2.Http2Error.PROTOCOL_ERROR;
import static io.norberg.http2.Http2Exception.connectionError;
import static io.norberg.http2.Http2WireFormat.FRAME_HEADER_SIZE;
import static io.norberg.http2.PseudoHeaders.AUTHORITY;
import static io.norberg.http2.PseudoHeaders.METHOD;
import static io.norberg.http2.PseudoHeaders.PATH;
import static io.norberg.http2.PseudoHeaders.SCHEME;
import static io.norberg.http2.PseudoHeaders.STATUS;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.util.AsciiString;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ServerConnection extends AbstractConnection<ServerConnection, ServerConnection.ServerStream> {

  private static final Logger log = LoggerFactory.getLogger(ServerConnection.class);

  private final RequestHandler requestHandler;

  private ServerConnection(final Builder builder, final Channel ch) {
    super(builder, ch, log);
    this.requestHandler = Objects.requireNonNull(builder.requestHandler(), "requestHandler");
  }

  @Override
  protected void encodeHeaders(final ServerStream stream, final HpackEncoder headerEncoder,
      final ByteBuf buf) throws Http2Exception {
    final Http2Response response = stream.response;
    headerEncoder.encodeResponse(buf, response.status().codeAsText());
    for (int i = 0; i < response.numHeaders(); i++) {
      headerEncoder.encodeHeader(buf, response.headerName(i), response.headerValue(i), false);
    }
  }

  @Override
  protected int headersPayloadSize(final ServerStream stream) {
    final Http2Response response = stream.response;
    return FRAME_HEADER_SIZE +
        Http2Header.size(STATUS, response.status().codeAsText()) +
        Http2WireFormat.headersPayloadSize(response);

  }

  @Override
  protected ServerConnection self() {
    return this;
  }

  @Override
  protected ChannelHandler handshakeHandler() {
    return new HandshakeHandler(localSettings());
  }

  @Override
  protected void connected() {

  }

  @Override
  protected void disconnected() {

  }

  @Override
  protected void peerSettingsChanged(final Http2Settings settings) {

  }

  @Override
  protected void readData(final ServerStream stream, final ByteBuf data, final int padding,
      final boolean endOfStream) throws Http2Exception {
    // TODO: allow user to provide codec that can be used to parse payload directly without copying it

    ByteBuf content = stream.request.content();
    if (content == null) {
      stream.request.content(Unpooled.copiedBuffer(data));
    } else {
      content.writeBytes(data);
    }
  }

  @Override
  protected ServerStream inbound(final int streamId) throws Http2Exception {
    final ServerStream stream = stream(streamId);
    if (stream == null) {
      final ServerStream newStream = new ServerStream(streamId, localInitialStreamWindow());
      registerStream(newStream);
      return newStream;
    }
    return stream;
  }

  @Override
  protected void inboundEnd(final ServerStream stream) throws Http2Exception {
    // Hand off request to request handler
    Http2Request request = stream.request;
    stream.request = null;
    try {
      requestHandler.handleRequest(stream, request);
    } catch (Exception e) {
      log.error("Request handler threw exception", e);
      stream.fail();
    }
//    request.release();
  }


  @Override
  protected boolean handlesOutbound(final Object msg, final ChannelPromise promise) {
    return msg instanceof Http2Response;
  }

  @Override
  protected ServerStream outbound(final Object msg, final ChannelPromise promise)
      throws Http2Exception {
    final Http2Response response = (Http2Response) msg;
    final ResponsePromise responsePromise = (ResponsePromise) promise;
    // TODO: handle duplicate responses
    final ServerStream stream = responsePromise.stream;
    stream.response = response;
    stream.data = response.content();
    stream.endOfStream = true;
    return stream;
  }

  @Override
  protected void outboundEnd(final ServerStream stream) {
    stream.response.release();
    stream.response = null;
    deregisterStream(stream.id);
  }

  @Override
  protected void endHeaders(final ServerStream stream, final boolean endOfStream)
      throws Http2Exception {
  }

  @Override
  protected void startHeaders(final ServerStream stream, final boolean endOfStream)
      throws Http2Exception {
  }

  @Override
  protected void readHeader(final ServerStream stream, final AsciiString name,
      final AsciiString value) throws Http2Exception {
    stream.request.header(name, value);
  }

  @Override
  protected void readPseudoHeader(final ServerStream stream, final AsciiString name,
      final AsciiString value) throws Http2Exception {
    if (name.length() < 5) {
      throw new IllegalArgumentException();
    }
    final byte b1 = name.byteAt(1);
    switch (b1) {
      case 'm': {
        if (!name.equals(METHOD)) {
          throw connectionError(PROTOCOL_ERROR, "Got invalid pseudo-header: " + name + "=" + value);
        }
        stream.request.method(HttpMethod.valueOf(value.toString()));
        return;
      }
      case 's': {
        if (!name.equals(SCHEME)) {
          throw connectionError(PROTOCOL_ERROR, "Got invalid pseudo-header: " + name + "=" + value);
        }
        stream.request.scheme(value);
        return;
      }
      case 'a': {
        if (!name.equals(AUTHORITY)) {
          throw connectionError(PROTOCOL_ERROR, "Got invalid pseudo-header: " + name + "=" + value);
        }
        stream.request.authority(value);
        return;
      }
      case 'p': {
        if (!name.equals(PATH)) {
          throw connectionError(PROTOCOL_ERROR, "Got invalid pseudo-header: " + name + "=" + value);
        }
        stream.request.path(value);
        return;
      }
      default:
        throw new Http2Exception(PROTOCOL_ERROR);
    }
  }

  class ServerStream extends Http2Stream implements Http2RequestContext {

    private Http2Request request = new Http2Request();
    private Http2Response response;

    public ServerStream(final int id, final int localWindow) {
      super(id);
      this.localWindow = localWindow;
    }

    public void respond(final Http2Response response) {
      send(response, new ResponsePromise(channel(), this));
    }

    public void fail() {
      // Return 500 for request handler errors
      respond(new Http2Response(INTERNAL_SERVER_ERROR));
    }
  }

  private static class ResponsePromise extends DefaultChannelPromise {

    final ServerStream stream;

    public ResponsePromise(final Channel channel, final ServerStream stream) {
      super(channel);
      this.stream = stream;
    }
  }

  static Builder builder() {
    return new Builder();
  }

  static class Builder extends AbstractConnection.Builder<Builder> {

    private RequestHandler requestHandler;

    RequestHandler requestHandler() {
      return requestHandler;
    }

    Builder requestHandler(final RequestHandler requestHandler) {
      this.requestHandler = requestHandler;
      return this;
    }

    @Override
    protected Builder self() {
      return this;
    }

    public ServerConnection build(final Channel ch) {
      return new ServerConnection(this, ch);
    }
  }

  private class HandshakeHandler extends ByteToMessageDecoder {

    private final Http2Settings settings;

    private int prefaceIndex;

    private HandshakeHandler(final Http2Settings settings) {
      this.settings = settings;
    }

    private void writeSettings(final ChannelHandlerContext ctx) {
      final int frameLength = Http2WireFormat.settingsFrameLength(settings);
      final ByteBuf buf = ctx.alloc().buffer(frameLength);
      Http2WireFormat.writeSettings(buf, settings);
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
        writeSettings(ctx);
        ctx.flush();
        handshakeDone();
        ctx.pipeline().remove(this);
      }
    }
  }
}
