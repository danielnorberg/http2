package io.norberg.http2;

import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpMethod.POST;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.util.CharsetUtil.UTF_8;
import static io.norberg.http2.TestUtil.randomByteBuf;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toList;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.util.AsciiString;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RunWith(MockitoJUnitRunner.class)
public class Http2ClientServerTest {

  private static final Logger log = LoggerFactory.getLogger(Http2ClientServerTest.class);

  private final List<Http2Server> servers = new ArrayList<>();
  private final List<Http2Client> clients = new ArrayList<>();

  @Mock Http2Client.Listener listener;

  @Rule
  public TestRule watcher = new LogTest();

  @After
  public void tearDown() throws Exception {
    servers.forEach(Http2Server::close);
    clients.forEach(Http2Client::close);
  }

  @Test
  public void testServerStreamHandler() throws ExecutionException, InterruptedException, TimeoutException {

    final RequestHandler requestHandler = stream -> new RequestStreamHandler() {

      private final ByteBuf payload = Unpooled.buffer();

      @Override
      public void method(HttpMethod method) {
        log.info("method: {}", method);
      }

      @Override
      public void scheme(AsciiString scheme) {
        log.info("scheme: {}", scheme);
      }

      @Override
      public void authority(AsciiString authority) {
        log.info("authority: {}", authority);
      }

      @Override
      public void path(AsciiString path) {
        log.info("path: {}", path);
      }

      @Override
      public void header(AsciiString name, AsciiString value) {
        log.info("header: {}: {}", name, value);
      }

      @Override
      public void data(ByteBuf data, int padding) {
        if (ByteBufUtil.isText(data, StandardCharsets.UTF_8)) {
          log.info("data (utf8): {}", data.toString(StandardCharsets.UTF_8));
        } else {
          log.info("data: {}", ByteBufUtil.hexDump(data));
        }
        payload.writeBytes(data);
      }

      @Override
      public void trailer(AsciiString name, AsciiString value) {
        log.info("trailer: {}: {}", name, value);
      }

      @Override
      public void end() {
        // TODO: test more permutations of fragmented responding

        stream.send(Http2Response.streaming()
            .status(OK)
            .header("foo-header", "bar-header")
            .contentUtf8("Hello "));

        stream.data(payload);

        stream.end(Http2Response.streaming()
            .contentUtf8(" Good bye!")
            .trailingHeader("baz-trailer", "quux-trailer"));
      }
    };

    // Start server
    final Http2Server server = autoClosing(Http2Server.of(requestHandler));
    final int port = server.bind(0).get().getPort();

    // Start client
    final Http2Client client = autoClosing(
        Http2Client.of("127.0.0.1", port));

    // Send a request
    final Http2Request request = Http2Request.of(POST, AsciiString.of("/hello"))
        .content(ByteBufUtil.writeUtf8(UnpooledByteBufAllocator.DEFAULT, "world!"))
        .header(AsciiString.of("foo"), AsciiString.of("bar"))
        .header(AsciiString.of("baz"), AsciiString.of("quux"));
    final CompletableFuture<Http2Response> future = client.send(request);
    final Http2Response response = future.get(3000, SECONDS);
    assertThat(response.status(), is(OK));
    assertThat(response.contentUtf8(), is("Hello world! Good bye!"));
    assertThat(response.numTrailingHeaders(), is(1));
    assertThat(response.headerName(0), is(AsciiString.of("foo-header")));
    assertThat(response.headerValue(0), is(AsciiString.of("bar-header")));
    assertThat(response.headerName(1), is(AsciiString.of("baz-trailer")));
    assertThat(response.headerValue(1), is(AsciiString.of("quux-trailer")));
  }

  @Test
  public void testReqRep() throws Exception {

    final FullRequestHandler requestHandler = (context, request) ->
        context.send(request
            .response(OK)
            .contentUtf8("hello: " + request.path()));

    // Start server
    final Http2Server server = autoClosing(Http2Server.ofFull(requestHandler));
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
  public void testReqRepWithHeadersAndTrailers() throws Exception {

    final FullRequestHandler requestHandler = (context, request) ->
        context.send(request
            .response(OK)
            .header("h1", "hv1")
            .contentUtf8("hello: " + request.path())
            .trailingHeader("t1", "tv1"));

    // Start server
    final Http2Server server = autoClosing(Http2Server.ofFull(requestHandler));
    final int port = server.bind(0).get().getPort();

    // Start client
    final Http2Client client = autoClosing(
        Http2Client.of("127.0.0.1", port));

    // Make a request
    final CompletableFuture<Http2Response> future = client.get("/world/1");
    final Http2Response response = future.get(30, SECONDS);

    // Check that the response has the expected content and header/trailer values
    assertThat(response.status(), is(OK));
    final String payload = response.contentUtf8();
    assertThat(payload, is("hello: /world/1"));
    assertThat(response.headerName(0), is(AsciiString.of("h1")));
    assertThat(response.headerValue(0), is(AsciiString.of("hv1")));
    assertThat(response.headerName(1), is(AsciiString.of("t1")));
    assertThat(response.headerValue(1), is(AsciiString.of("tv1")));
  }

  @Test
  public void testHeaderFragmentation() throws Exception {

    final FullRequestHandler requestHandler = (context, request) -> {
      Http2Response response = request.response(
          OK, Unpooled.copiedBuffer("hello: " + request.path(), UTF_8));
      request.forEachHeader(response::header);
      context.send(response);
    };

    // Start server
    final Http2Server server = autoClosing(Http2Server.ofFull(requestHandler));
    final int port = server.bind(0).get().getPort();

    // Start client
    final Http2Client client = autoClosing(
        Http2Client.of("127.0.0.1", port));

    byte[] cookieBytes = new byte[128 * 1024];
    ThreadLocalRandom.current().nextBytes(cookieBytes);
    String cookie = Base64.getEncoder().encodeToString(cookieBytes);

    // Make a request with a huge header
    Http2Request request = new Http2Request(GET, "/world/1");
    AsciiString cookieHeaderName = AsciiString.of("SetCookie");
    AsciiString cookieHeaderValue = AsciiString.of(cookie);
    request.header(cookieHeaderName, cookieHeaderValue);
    final CompletableFuture<Http2Response> future = client.send(request);
    final Http2Response response = future.get(30, SECONDS);
    assertThat(response.status(), is(OK));
    assertThat(response.headerName(0), is(cookieHeaderName));
    assertThat(response.headerValue(0), is(cookieHeaderValue));
    final String payload = response.content().toString(UTF_8);
    assertThat(payload, is("hello: /world/1"));
  }

  @Test
  public void testReqRepManyHeaders() throws Exception {

    final int n = 1024;

    Http2Headers headers = Http2Headers.of();
    for (int i = 0; i < n; i++) {
      headers.add("header" + i, "value" + i);
    }

    final FullRequestHandler requestHandler = (context, request) ->
        context.send(request
            .response(OK, Unpooled.copiedBuffer("hello: " + request.path(), UTF_8))
            .headers(headers));

    // Start server
    final Http2Server server = autoClosing(Http2Server.ofFull(requestHandler));
    final int port = server.bind(0).get().getPort();

    // Start client
    final Http2Client client = autoClosing(
        Http2Client.of("127.0.0.1", port));

    // Make a request
    final CompletableFuture<Http2Response> future = client.send(
        Http2Request.of(GET, "/world/1")
            .headers(headers));
    final Http2Response response = future.get(30, SECONDS);
    assertThat(response.status(), is(OK));
    final String payload = response.content().toString(UTF_8);
    assertThat(payload, is("hello: /world/1"));

    assertThat(response.headers().collect(toList()), is(headers.stream().collect(toList())));
  }

  @Test
  public void testLargeReqRep() throws Exception {

    // Large response
    final FullRequestHandler requestHandler = (context, request) ->
        context.send(request.response(
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
    final FullRequestHandler requestHandler = (context, request) ->
        context.send(request.response(
            OK, Unpooled.copiedBuffer("hello world", UTF_8)));

    // Start server
    final Http2Server server1 = autoClosing(Http2Server.ofFull(requestHandler));
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
    verify(listener, timeout(30_000).atLeastOnce()).connectionClosed(client);
    reset(listener);

    // Make another request, observe it fail
    final CompletableFuture<Http2Response> failure = client.get("/hello2");
    try {
      failure.get(30, SECONDS);
      fail();
    } catch (ExecutionException e) {
      assertThat(e.getCause(), is(instanceOf(ConnectionClosedException.class)));
    }

    // Start server again
    final Http2Server server2 = autoClosing(Http2Server.ofFull(requestHandler));
    server2.bind(port).get();
    verify(listener, timeout(30_000).atLeastOnce()).connectionEstablished(client);

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
