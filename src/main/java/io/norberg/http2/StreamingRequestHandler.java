package io.norberg.http2;

import java.util.function.Function;

public abstract class StreamingRequestHandler implements RequestHandler {

  @Override
  public void handleRequest(Http2RequestContext context, Http2Request request) {
    throw new UnsupportedOperationException();
  }

  @Override
  public abstract RequestStreamHandler handleRequest(Http2RequestContext stream);

  public static StreamingRequestHandler of(Function<Http2RequestContext, RequestStreamHandler> streamHandlerFactory) {
    // TODO: clean this up, this kind of wrapping should not be needed
    return new StreamingRequestHandler() {
      @Override
      public RequestStreamHandler handleRequest(Http2RequestContext stream) {
        return streamHandlerFactory.apply(stream);
      }
    };
  }
}
