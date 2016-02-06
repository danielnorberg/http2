package io.norberg.h2client;

public interface Http2RequestContext {

  void respond(final Http2Response response);

  void fail();
}
