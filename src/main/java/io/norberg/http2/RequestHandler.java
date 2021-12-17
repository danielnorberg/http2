package io.norberg.http2;

@FunctionalInterface
public interface RequestHandler {

  RequestStreamHandler handleRequest(Http2RequestContext stream);

}
