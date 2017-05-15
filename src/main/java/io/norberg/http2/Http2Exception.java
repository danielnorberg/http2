package io.norberg.http2;

import java.util.Objects;

public class Http2Exception extends Exception {

  private final int streamId;
  private final Http2Error error;

  Http2Exception(Http2Error error) {
    this(error, 0);
  }

  Http2Exception(Http2Error error, String message) {
    this(error, 0, message);
  }

  Http2Exception(Http2Error error, int streamId) {
    super(error.toString());
    this.error = Objects.requireNonNull(error, "error");
    this.streamId = streamId;
  }

  Http2Exception(Http2Error error, int streamId, String message) {
    super(error + ": stream " + streamId + ": " + message);
    this.error = Objects.requireNonNull(error, "error");
    this.streamId = streamId;
  }

  public int streamId() {
    return streamId;
  }

  public Http2Error error() {
    return error;
  }

  public static Http2Exception connectionError(Http2Error error, String message) {
    return new Http2Exception(error, message);
  }

  public static Http2Exception connectionError(Http2Error error, String format, Object... args) {
    return new Http2Exception(error, String.format(format, args));
  }

  public static Http2Exception streamError(int id, Http2Error error, String message) {
    return new Http2Exception(error, id, message);
  }

  public static Http2Exception streamError(int id, Http2Error error, String format, Object... args) {
    return new Http2Exception(error, id, String.format(format, args));
  }
}
