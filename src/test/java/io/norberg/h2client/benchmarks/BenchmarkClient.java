package io.norberg.h2client.benchmarks;

import com.google.common.util.concurrent.Uninterruptibles;

import com.spotify.logging.LoggingConfigurator;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import io.netty.util.concurrent.DefaultThreadFactory;
import io.norberg.h2client.Http2Client;

import static java.util.concurrent.TimeUnit.SECONDS;

class BenchmarkClient {

  private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
      1, new DefaultThreadFactory("client", true));

  public static void main(final String... args) {
    run();
  }

  static void run() {
    LoggingConfigurator.configureNoLogging();

    final Http2Client client = new Http2Client("127.0.0.1", 4711);

    final int concurrency = 100;

    final ProgressMeter meter = new ProgressMeter();
    final ProgressMeter.Metric requests = meter.group("throughput").metric("requests", "requests");
    final ProgressMeter.Metric errors = meter.group("throughput").metric("errors", "errors");
    final ProgressMeter.Metric data = meter.group("throughput").metric("data", "bytes");

    for (int i = 0; i < concurrency; i++) {
      get(client, requests, errors, data);
    }

    while (true) {
      Uninterruptibles.sleepUninterruptibly(1, SECONDS);
      System.out.println(client);
    }
  }

  private static void get(final Http2Client client, final ProgressMeter.Metric requests,
                          final ProgressMeter.Metric errors, final ProgressMeter.Metric data) {
    final long start = System.nanoTime();
    client.get("/hello").whenComplete((response, ex) -> {
      final long end = System.nanoTime();
      final long latency = end - start;
      if (ex != null) {
        errors.inc(latency);
        scheduler.schedule(() -> get(client, requests, errors, data), 1, SECONDS);
        return;
      }
      requests.inc(latency);
      if (response.hasContent()) {
        data.add(response.content().readableBytes(), latency);
      }
      response.release();
      get(client, requests, errors, data);
    });
  }
}
