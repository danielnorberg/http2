/**
 * Copyright (C) 2016 Spotify AB
 */

package io.norberg.h2client;

import com.spotify.netty.util.BatchFlusher;

import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.util.AsciiString;

import static io.netty.handler.codec.http2.Http2Error.PROTOCOL_ERROR;
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.AUTHORITY;
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.METHOD;
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.PATH;
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.SCHEME;
import static io.norberg.h2client.Http2WireFormat.FRAME_HEADER_SIZE;
import static java.util.Objects.requireNonNull;

class ClientConnection2 extends AbstractConnection<ClientConnection2.ClientStream> {

  private final CompletableFuture<ClientConnection2> connectFuture = new CompletableFuture<>();
  private final CompletableFuture<ClientConnection2> disconnectFuture = new CompletableFuture<>();

  private final InetSocketAddress address;

  private final Listener listener;

  private int streamId = 1;

  private ClientConnection2(final Builder builder) {
    super(builder);

    this.address = requireNonNull(builder.address, "address");
    this.sslContext = requireNonNull(builder.sslContext, "sslContext");
    this.worker = requireNonNull(builder.worker, "worker");

    this.listener = builder.listener;
  }

  void send(final Http2Request request, final Http2ResponseHandler responseHandler) {
    final RequestPromise promise = new RequestPromise(channel(), responseHandler);
    send(request, promise);
  }

  CompletableFuture<ClientConnection2> connectFuture() {
    return connectFuture;
  }

  CompletableFuture<ClientConnection2> disconnectFuture() {
    return disconnectFuture;
  }


  private void dispatchResponse(final ClientStream stream) {
    stream.close();
    deregisterStream(stream.id);
    succeed(stream.requestPromise.responseHandler, stream.response);
  }

  private int nextStreamId() {
    streamId += 2;
    return streamId;
  }

  @Override
  protected void connected() {
    connectFuture.complete(this);
  }

  @Override
  protected void disconnected() {
    disconnectFuture.complete(this);
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
    final ClientStream stream = new ClientStream(streamId, localInitialStreamWindow(), request, requestPromise);

    registerStream(stream);

    return stream;
  }

  @Override
  protected int headersPayloadSize(final ClientStream stream) {
    final Http2Request request = stream.request;
    return FRAME_HEADER_SIZE +
           Http2Header.size(METHOD.value(), request.method().asciiName()) +
           Http2Header.size(AUTHORITY.value(), request.authority()) +
           Http2Header.size(SCHEME.value(), request.scheme()) +
           Http2Header.size(PATH.value(), request.path()) +
           (request.hasHeaders() ? Http2WireFormat.headersPayloadSize(request.headers()) : 0);
  }

  @Override
  protected int encodeHeaders(final ClientStream stream, final HpackEncoder headerEncoder,
                              final ByteBuf buf) throws Http2Exception {
    final int mark = buf.readableBytes();
    final Http2Request request = stream.request;
    headerEncoder.encodeRequest(buf,
                                request.method().asciiName(),
                                request.scheme(),
                                request.authority(),
                                request.path());
    if (request.hasHeaders()) {
      for (Map.Entry<CharSequence, CharSequence> header : request.headers()) {
        final AsciiString name = AsciiString.of(header.getKey());
        final AsciiString value = AsciiString.of(header.getValue());
        headerEncoder.encodeHeader(buf, name, value, false);
      }
    }
    return buf.readableBytes() - mark;
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
    stream.response.status(HttpResponseStatus.valueOf(value.parseInt()));
  }

  @Override
  protected void endHeaders(final ClientStream stream, final boolean endOfStream) {
    if (endOfStream) {
      dispatchResponse(stream);
    }
  }

  @Override
  protected void readData(final ClientStream stream, final ByteBuf data, final int padding,
                          final boolean endOfStream) {
    if (endOfStream) {
      dispatchResponse(stream);
    }
  }

  protected static class ClientStream extends Stream {

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
    listener.responseReceived(ClientConnection2.this, response);
    responseHandler.response(response);
  }

  private void fail(final Http2ResponseHandler responseHandler, final Throwable t) {
    listener.requestFailed(ClientConnection2.this);
    responseHandler.failure(t);
  }

  static Builder builder() {
    return new Builder();
  }

  static class Builder extends AbstractConnection.Builder<Builder> {

    private Listener listener;

    Builder listener(final Listener listener) {
      this.listener = listener;
      return this;
    }

    @Override
    protected Builder self() {
      return this;
    }

    public CompletableFuture<ClientConnection2> connect() {
      final ClientConnection2 connection = new ClientConnection2(this);
      return connection.connect();

    }
  }

  private CompletableFuture<ClientConnection2> connect() {
    final Bootstrap b = new Bootstrap()
        .group(worker)
        .channelFactory(() -> channel)
        .option(ChannelOption.SO_KEEPALIVE, true)
        .remoteAddress(address)
        .handler(new Initializer(sslContext))
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
}
