package io.norberg.h2client;

public interface RequestHandler {

  void handleRequest(final Http2RequestContext context, Http2Request request);
}
