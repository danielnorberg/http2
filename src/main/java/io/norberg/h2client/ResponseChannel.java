package io.norberg.h2client;

public interface ResponseChannel {

  void sendResponse(Http2Response response, int streamId);
}
