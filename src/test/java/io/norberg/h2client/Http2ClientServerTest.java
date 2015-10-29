package io.norberg.h2client;

import com.google.common.util.concurrent.Uninterruptibles;

import com.spotify.logging.LoggingConfigurator;

import org.junit.Ignore;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;

import io.netty.handler.codec.http.FullHttpResponse;

import static com.spotify.logging.LoggingConfigurator.Level.INFO;
import static java.util.concurrent.TimeUnit.SECONDS;

public class Http2ClientServerTest {

  @Test
  public void testReqRep() throws Exception {
    final Http2Server server = new Http2Server();
    server.bindFuture().syncUninterruptibly();
    final int port = server.port();

    final Http2Client client = new Http2Client("127.0.0.1", port);
    final CompletableFuture<FullHttpResponse> future = client.get("/hello/world");
    final FullHttpResponse response = future.get();
  }

  @Ignore("this is not a test")
  @Test
  public void reqRepBenchmark() throws Exception {
    LoggingConfigurator.configureDefaults("benchmark", INFO);

    final Http2Server server = new Http2Server();
    server.bindFuture().syncUninterruptibly();
    final int port = server.port();

    final Http2Client client = new Http2Client("127.0.0.1", port);

    final int concurrency = 100;

    final ProgressMeter meter = new ProgressMeter();
    final ProgressMeter.Metric metric = meter.group("throughput").metric("requests", "requests");

    for (int i = 0; i < concurrency; i++) {
      send(client, metric);
    }

    while(true) {
      Uninterruptibles.sleepUninterruptibly(1, SECONDS);
    }
  }

  private void send(final Http2Client client, final ProgressMeter.Metric metric) {
    final long start = System.nanoTime();
    client.get("/hello").whenComplete((response, ex) -> {
      if (ex != null) {
        ex.printStackTrace();
        return;
      }
      final long end = System.nanoTime();
      final long latency = end - start;
      metric.inc(latency);
      send(client, metric);
    });
  }
}
