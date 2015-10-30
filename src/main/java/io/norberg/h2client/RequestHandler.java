/**
 * Copyright (C) 2015 Spotify AB
 */

package io.norberg.h2client;

import java.util.concurrent.CompletableFuture;

import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;

public interface RequestHandler {

  CompletableFuture<FullHttpResponse> handleRequest(FullHttpRequest request);
}
