package io.norberg.http2;

public interface Http2ResponseHandler {

  void response(Http2Response response);

  void failure(Throwable e);
}
