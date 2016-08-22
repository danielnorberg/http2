package io.norberg.h2client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.util.AsciiString;

import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.netty.handler.codec.http2.Http2CodecUtil.FRAME_HEADER_LENGTH;
import static io.netty.handler.codec.http2.Http2CodecUtil.SETTING_ENTRY_LENGTH;
import static io.netty.handler.codec.http2.Http2Error.PROTOCOL_ERROR;
import static io.netty.handler.codec.http2.Http2FrameTypes.SETTINGS;
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.AUTHORITY;
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.METHOD;
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.PATH;
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.SCHEME;
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.STATUS;
import static io.norberg.h2client.Http2WireFormat.FRAME_HEADER_SIZE;
import static io.norberg.h2client.Http2WireFormat.writeFrameHeader;

class ServerConnection2 extends AbstractConnection<ServerConnection2, ServerConnection2.ServerStream> {

  private static final Logger log = LoggerFactory.getLogger(ServerConnection2.class);

  private final RequestHandler requestHandler;

  private ServerConnection2(final Builder builder, final Channel ch) {
    super(builder, ch);
    this.requestHandler = Objects.requireNonNull(builder.requestHandler(), "requestHandler");
  }

  private void maybeDispatch(final boolean endOfStream, final ServerStream stream) {
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
  protected int encodeHeaders(final ServerStream stream, final HpackEncoder headerEncoder,
                              final ByteBuf buf) throws Http2Exception {
    final Http2Response response = stream.response;
    final int mark = buf.readableBytes();
    headerEncoder.encodeResponse(buf, response.status().codeAsText());
    if (response.hasHeaders()) {
      for (Map.Entry<CharSequence, CharSequence> header : response.headers()) {
        final AsciiString name = AsciiString.of(header.getKey());
        final AsciiString value = AsciiString.of(header.getValue());
        headerEncoder.encodeHeader(buf, name, value, false);
      }
    }
    final int size = buf.readableBytes() - mark;
    return size;
  }

  @Override
  protected int headersPayloadSize(final ServerStream stream) {
    final Http2Response response = stream.response;
    return FRAME_HEADER_SIZE +
           Http2Header.size(STATUS.value(), response.status().codeAsText()) +
           (response.hasHeaders() ? Http2WireFormat.headersPayloadSize(response.headers()) : 0);

  }

  @Override
  protected ServerConnection2 self() {
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
    return stream;
  }

  @Override
  protected void endHeaders(final ServerStream stream, final boolean endOfStream)
      throws Http2Exception {
    maybeDispatch(endOfStream, stream);
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

  class ServerStream extends Stream implements Http2RequestContext {

    private final Http2Request request = new Http2Request();
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

    public ServerConnection2 build(final Channel ch) {
      return new ServerConnection2(this, ch);
    }
  }

  private class HandshakeHandler extends ByteToMessageDecoder {

    private final Http2Settings settings;

    private int prefaceIndex;

    private HandshakeHandler(final Http2Settings settings) {
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
        handshakeDone();
        ctx.pipeline().remove(this);
      }
    }
  }
}
