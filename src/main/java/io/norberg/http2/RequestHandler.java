package io.norberg.http2;

public interface RequestHandler {

  // TODO: clean up and restore this to a FunctionalInterface

  void handleRequest(final Http2RequestContext context, Http2Request request);

  default RequestStreamHandler handleRequest(Http2RequestContext stream) {
    return new DefaultRequestStreamHandler(this, stream);
  }
}
