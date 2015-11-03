/**
 * Copyright (C) 2015 Spotify AB
 */

package io.norberg.h2client;

import java.util.concurrent.CompletableFuture;

public interface RequestHandler {

  CompletableFuture<Http2Response> handleRequest(Http2Request request);
}
