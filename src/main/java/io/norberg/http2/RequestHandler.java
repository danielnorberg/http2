package io.norberg.http2;

public interface RequestHandler {

  void handleRequest(final Http2RequestContext context, Http2Request request);
}
