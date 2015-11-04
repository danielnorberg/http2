package io.norberg.h2client;

import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.FullHttpResponse;

import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.util.CharsetUtil.UTF_8;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

public class Http2ClientServerTest {

  @Test
  public void testReqRep() throws Exception {
    final RequestHandler requestHandler = (request) ->
        CompletableFuture.completedFuture(request.response(
            OK.code(), Unpooled.copiedBuffer("hello world", UTF_8)));

    // Start server
    final Http2Server server = new Http2Server(requestHandler);
    server.bindFuture().syncUninterruptibly();
    final int port = server.port();

    // Start client
    final Http2Client client = new Http2Client("127.0.0.1", port);

    // Make a request
    final CompletableFuture<FullHttpResponse> future = client.get("/hello/world");
    final FullHttpResponse response = future.get();
    final String payload = response.content().toString(UTF_8);
    assertThat(payload, is("hello world"));
  }

  @Test
  public void testClientReconnects() throws Exception {
    final RequestHandler requestHandler = (request) ->
        CompletableFuture.completedFuture(request.response(
            OK.code(), Unpooled.copiedBuffer("hello world", UTF_8)));

    // Start server
    final Http2Server server1 = new Http2Server(requestHandler);
    server1.bindFuture().syncUninterruptibly();
    final int port = server1.port();

    // Make a successful request
    final Http2Client client = new Http2Client("127.0.0.1", port);
    client.get("/hello1").get();

    // Stop server
    server1.close().get();

    // TODO: make deterministic
    Thread.sleep(100);

    // Make another request, observe it fail
    final CompletableFuture<FullHttpResponse> failure = client.get("/hello2");
    try {
      failure.get();
      fail();
    } catch (ExecutionException e) {
      assertThat(e.getCause(), is(instanceOf(ConnectionClosedException.class)));
    }

    // Start server again
    final Http2Server server2 = new Http2Server(requestHandler, port);
    server2.bindFuture().syncUninterruptibly();

    // Make another successful request after client reconnects
    client.get("/hello2").get();
  }
}
