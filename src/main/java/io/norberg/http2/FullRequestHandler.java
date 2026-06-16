package io.norberg.http2;

@FunctionalInterface
public interface FullRequestHandler extends RequestHandler {

  default RequestStreamHandler handleRequest(Http2RequestContext stream) {
    return RequestAggregator.of(this, stream);
  }

  void handleRequest(final Http2RequestContext context, Http2Request request);

  static FullRequestHandler of(FullRequestHandler handler) {
    return handler;
  }
}
