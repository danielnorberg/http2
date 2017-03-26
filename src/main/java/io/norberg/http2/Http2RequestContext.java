package io.norberg.http2;

public interface Http2RequestContext {

  void respond(final Http2Response response);

  void fail();
}
