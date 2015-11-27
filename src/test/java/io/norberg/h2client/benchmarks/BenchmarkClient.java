package io.norberg.h2client.benchmarks;

import com.spotify.logging.LoggingConfigurator;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.handler.codec.http2.Http2Settings;
import io.netty.util.AsciiString;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.norberg.h2client.Http2Client;

import static java.util.concurrent.TimeUnit.SECONDS;

class BenchmarkClient {

  private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
      1, new DefaultThreadFactory("client", true));

  private static final AsciiString PATH = AsciiString.of("/hello");

  public static void main(final String... args) throws Exception {
    run();
  }

  static void run() throws Exception {
    LoggingConfigurator.configureNoLogging();

    final AtomicInteger maxConcurrentStreams = new AtomicInteger(2000);

    final Http2Client.Listener listener = new Http2Client.ListenerAdapter() {
      @Override
      public void peerSettingsChanged(final Http2Client client, final Http2Settings settings) {
        if (settings.maxConcurrentStreams() != null) {
          maxConcurrentStreams.set(settings.maxConcurrentStreams().intValue());
        }
      }
    };

    final Http2Client client = Http2Client.builder()
        .listener(listener)
        .maxConcurrentStreams(maxConcurrentStreams.intValue())
        .address("127.0.0.1", 4711)
        .build();

    final ProgressMeter meter = new ProgressMeter();
    final ProgressMeter.Metric requests = meter.group("throughput").metric("requests", "requests");
    final ProgressMeter.Metric errors = meter.group("throughput").metric("errors", "errors");
    final ProgressMeter.Metric data = meter.group("throughput").metric("data", "bytes");

    // TODO: lower concurrency in response to settings change as well
    int concurrentStreams = 0;
    while (true) {
      if (concurrentStreams < maxConcurrentStreams.get()) {
        concurrentStreams++;
        get(client, requests, errors, data);
      } else {
        Thread.sleep(1000);
      }
    }
  }

  private static void get(final Http2Client client, final ProgressMeter.Metric requests,
                          final ProgressMeter.Metric errors, final ProgressMeter.Metric data) {
    final long start = System.nanoTime();
    client.get(PATH).whenComplete((response, ex) -> {
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
