package io.norberg.http2;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.net.SocketAddress;

public interface Http2RequestContext {

  /**
   * Send a complete response and end the stream. No additional data or headers can be sent after this.
   *
   * @param response The response to send. Must have a status set.
   */
  default void respond(Http2Response response) {
    send(response, true);
  }

  /**
   * Respond with a status only and end the stream. No additional data or headers can be sent after this.
   *
   * @param status The response status to send.
   */
  default void respond(HttpResponseStatus status) {
    respond(Http2Response.of(status));
  }

  /**
   * Send a response and keep the stream open for additional data and trailers.
   *
   * @param response The response to send. Must have a status set.
   */
  default void send(Http2Response response) {
    send(response, false);
  }

  /**
   * Send a response and optionally keep the stream open for additional data and trailers.
   *
   * @param response The response to send.
   * @param end      Whether to keep the stream open or not.
   */
  void send(Http2Response response, boolean end);

  /**
   * Send additional response payload body data.
   *
   * @param data The data to send.
   */
  default void send(ByteBuf data) {
    send(data, false);
  }

  /**
   * Send additional response payload data and optionally end the stream.
   *
   * @param data The data to send.
   * @param end  Whether to end the stream or not.
   */
  void send(ByteBuf data, boolean end);

  /**
   * Send the final part of the response. No additional data or headers can be sent after this.
   *
   * @param response The final part of the response to send. Cannot have any initial headers or status set.
   */
  default void end(Http2Response response) {
    send(response, true);
  }

  /**
   * Send response trailers and end the stream. No additional data or headers can be sent after this.
   *
   * @param trailers The trailer headers to send.
   */
  default void end(Http2Headers trailers) {
    end(Http2Response.of().trailingHeaders(trailers));
  }

  /**
   * Send a final response payload body data.
   *
   * @param data The data to send.
   */
  default void end(ByteBuf data) {
    end(Http2Response.of().content(data));
  }

  /**
   * End the stream without sending any additional data or headers.
   */
  default void end() {
    end(Http2Response.of());
  }

  /**
   * Abort the request and terminate the stream.
   */
  void reset(Http2Error error);

  /**
   * The address of the remote peer;
   */
  SocketAddress remoteAddress();
}
