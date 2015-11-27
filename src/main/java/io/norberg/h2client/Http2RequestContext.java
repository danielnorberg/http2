package io.norberg.h2client;

import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;

// TODO: make this an interface
public class Http2RequestContext {

  private final ResponseChannel channel;
  private final int streamId;

  Http2RequestContext(final ResponseChannel channel, final int streamId) {
    this.channel = channel;
    this.streamId = streamId;
  }

  public void respond(final Http2Response response) {
    channel.sendResponse(response, streamId);
  }

  public void fail() {
    // Return 500 for request handler errors
    respond(new Http2Response(INTERNAL_SERVER_ERROR));
  }
}
