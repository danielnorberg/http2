package io.norberg.h2client;

public interface Http2ResponseHandler {

  void response(Http2Response response);

  void failure(Throwable e);
}
