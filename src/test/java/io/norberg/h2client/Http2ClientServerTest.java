package io.norberg.h2client;

import com.google.common.util.concurrent.Uninterruptibles;

import com.spotify.logging.LoggingConfigurator;

import org.junit.Ignore;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;

import static com.spotify.logging.LoggingConfigurator.Level.DEBUG;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static io.netty.util.CharsetUtil.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

public class Http2ClientServerTest {

  @Test
  public void testReqRep() throws Exception {
    final RequestHandler requestHandler = (request) ->
        CompletableFuture.completedFuture(new DefaultFullHttpResponse(
            HTTP_1_1, OK, Unpooled.copiedBuffer("hello world", UTF_8)));

    final Http2Server server = new Http2Server(requestHandler);
    server.bindFuture().syncUninterruptibly();
    final int port = server.port();

    final Http2Client client = new Http2Client("127.0.0.1", port);
    final CompletableFuture<FullHttpResponse> future = client.get("/hello/world");

    final FullHttpResponse response = future.get();

    final String payload = response.content().toString(UTF_8);
    assertThat(payload, is("hello world"));
  }

  @Test
  public void testClientReconnects() throws Exception {
    final RequestHandler requestHandler = (request) ->
        CompletableFuture.completedFuture(new DefaultFullHttpResponse(
            HTTP_1_1, OK, Unpooled.copiedBuffer("hello world", UTF_8)));

    // Start server
    final Http2Server server1 = new Http2Server(requestHandler);
    server1.bindFuture().syncUninterruptibly();
    final int port = server1.port();

    // Make a successful request
    final Http2Client client = new Http2Client("127.0.0.1", port);
    client.get("/hello1").get();

    // Stop server
    server1.close().get();

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

  @Ignore("this is not a test")
  @Test
  public void reqRepBenchmark() throws Exception {
    LoggingConfigurator.configureDefaults("benchmark", DEBUG);

    final int payloadSize = 128;
    final ByteBuf[] payloads = payloads(payloadSize, 1000);

    final RequestHandler requestHandler = (request) ->
        CompletableFuture.completedFuture(new DefaultFullHttpResponse(HTTP_1_1, OK, request.content()));

    final Http2Server server = new Http2Server(requestHandler);
    server.bindFuture().syncUninterruptibly();
    final int port = server.port();

    final Http2Client client = new Http2Client("127.0.0.1", port);

    final int concurrency = 100;

    final ProgressMeter meter = new ProgressMeter();
    final ProgressMeter.Metric metric = meter.group("throughput").metric("requests", "requests");

    for (int i = 0; i < concurrency; i++) {
      send(client, metric, payloads);
    }

    while(true) {
      Uninterruptibles.sleepUninterruptibly(1, SECONDS);
    }
  }

  private ByteBuf[] payloads(final int size, final int n) {
    return IntStream.range(0, n).mapToObj(i -> payload(size)).toArray(ByteBuf[]::new);
  }

  private ByteBuf payload(final int size) {
    final ThreadLocalRandom r = ThreadLocalRandom.current();
    final ByteBuf payload = Unpooled.buffer(size);
    for (int i = 0; i < size; i++) {
      payload.writeByte(r.nextInt());
    }
    return Unpooled.unreleasableBuffer(payload);
  }

  private void send(final Http2Client client, final ProgressMeter.Metric metric, final ByteBuf[] payloads) {
    final ByteBuf payload = payloads[ThreadLocalRandom.current().nextInt(payloads.length)].duplicate();
    final long start = System.nanoTime();
    client.post("/hello", payload).whenComplete((response, ex) -> {
      if (ex != null) {
        ex.printStackTrace();
        return;
      }
      final long end = System.nanoTime();
      final long latency = end - start;
      metric.inc(latency);
      send(client, metric, payloads);
    });
  }
}
