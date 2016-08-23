package io.norberg.h2client;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.util.CharsetUtil.UTF_8;
import static io.norberg.h2client.TestUtil.randomByteBuf;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@RunWith(MockitoJUnitRunner.class)
public class Http2ClientServerTest {

  private final List<Http2Server> servers = new ArrayList<>();
  private final List<Http2Client> clients = new ArrayList<>();

  @Mock Http2Client.Listener listener;

  @After
  public void tearDown() throws Exception {
    servers.forEach(Http2Server::close);
    clients.forEach(Http2Client::close);
  }

  @Test
  public void testReqRep() throws Exception {

    final RequestHandler requestHandler = (context, request) ->
        context.respond(request.response(
            OK, Unpooled.copiedBuffer("hello: " + request.path(), UTF_8)));

    // Start server
    final Http2Server server = autoClosing(Http2Server.create(requestHandler));
    final int port = server.bind(0).get().getPort();

    // Start client
    final Http2Client client = autoClosing(
        Http2Client.of("127.0.0.1", port));

    // Make a request (queued and sent when the connection is up)
    {
      final CompletableFuture<Http2Response> future = client.get("/world/1");
      final Http2Response response = future.get(30, SECONDS);
      assertThat(response.status(), is(OK));
      final String payload = response.content().toString(UTF_8);
      assertThat(payload, is("hello: /world/1"));
    }

    // Make another request (sent directly)
    {
      final CompletableFuture<Http2Response> future = client.get("/world/2");
      final Http2Response response = future.get(30, SECONDS);
      assertThat(response.status(), is(OK));
      final String payload = response.content().toString(UTF_8);
      assertThat(payload, is("hello: /world/2"));
    }
  }

  @Test
  public void testLargeReqRep() throws Exception {

    // Large response
    final RequestHandler requestHandler = (context, request) ->
        context.respond(request.response(
            OK, Unpooled.copiedBuffer(request.content())));

    // Start server
    final Http2Server server = autoClosing(
        Http2Server.builder()
            .requestHandler(requestHandler)
            .connectionWindow(1024 * 1024)
            .streamWindow(256 * 1024)
            .build());
    final int port = server.bind(0).get().getPort();

    // Start client
    final Http2Client client = autoClosing(
        Http2Client.builder()
            .address("127.0.0.1", port)
            .connectionWindow(1024 * 1024)
            .streamWindow(256 * 1024)
            .build());

    // Make a large request
    final ByteBuf requestPayload = randomByteBuf(4 * 1024 * 1024);
    final ByteBuf expectedResponsePaylod = Unpooled.copiedBuffer(requestPayload);
    final CompletableFuture<Http2Response> future = client.post("/world", requestPayload);
    while (!future.isDone()) {
      Thread.sleep(1000);
    }
    final Http2Response response = future.get();
    final ByteBuf responsePayload = response.content();
    assertThat(responsePayload, is(expectedResponsePaylod));
  }

  @Test
  public void testClientReconnects() throws Exception {
    final RequestHandler requestHandler = (context, request) ->
        context.respond(request.response(
            OK, Unpooled.copiedBuffer("hello world", UTF_8)));

    // Start server
    final Http2Server server1 = autoClosing(Http2Server.create(requestHandler));
    final int port = server1.bind(0).get().getPort();

    // Make a successful request
    final Http2Client client = autoClosing(
        Http2Client.builder()
            .listener(listener)
            .address("127.0.0.1", port)
            .build());
    client.get("/hello1").get(30, SECONDS);

    // Stop server
    server1.close().get(30, SECONDS);

    // Wait for client to notice that the connection closed
    verify(listener, timeout(30000)).connectionClosed(client);

    // Make another request, observe it fail
    final CompletableFuture<Http2Response> failure = client.get("/hello2");
    try {
      failure.get(30, SECONDS);
      fail();
    } catch (ExecutionException e) {
      assertThat(e.getCause(), is(instanceOf(ConnectionClosedException.class)));
    }

    // Start server again
    final Http2Server server2 = autoClosing(Http2Server.create(requestHandler));
    server2.bind(port).get();
    verify(listener, timeout(30000).times(2)).connectionEstablished(client);

    // Make another successful request after client reconnects
    client.get("/hello2").get(30, SECONDS);
  }

  private Http2Server autoClosing(final Http2Server server) {
    servers.add(server);
    return server;
  }

  private Http2Client autoClosing(final Http2Client client) {
    clients.add(client);
    return client;
  }
}
