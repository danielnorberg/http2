package io.norberg.h2client.benchmarks;

import com.spotify.logging.LoggingConfigurator;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.handler.codec.http2.Http2Settings;
import io.netty.util.AsciiString;
import io.netty.util.ResourceLeakDetector;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.norberg.h2client.Http2Client;
import io.norberg.h2client.Http2Request;
import io.norberg.h2client.Http2Response;
import io.norberg.h2client.Http2ResponseHandler;

import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.util.ResourceLeakDetector.Level.DISABLED;
import static java.util.concurrent.TimeUnit.SECONDS;

class BenchmarkClient {

  private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
      1, new DefaultThreadFactory("client", true));

  private static final AsciiString PATH = AsciiString.of("/hello");

  public static void main(final String... args) throws Exception {
    LoggingConfigurator.configureNoLogging();
    ResourceLeakDetector.setLevel(DISABLED);
    run();
  }

  static void run() throws Exception {

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
        .connectionWindow(1024 * 1024)
        .address("127.0.0.1", 4711)
        .build();

    final ProgressMeter meter = new ProgressMeter();
    final ProgressMeter.Metric requests = meter.group("throughput").metric("requests", "requests");
    final ProgressMeter.Metric errors = meter.group("throughput").metric("errors", "errors");
    final ProgressMeter.Metric data = meter.group("throughput").metric("data", "bytes");

    final List<AsciiString> headers = new ArrayList<>();
    final int n = 1024;
    for (int i = 0; i < n; i++) {
      final AsciiString name = AsciiString.of("header" + i);
      final AsciiString value = AsciiString.of("value" + i);
      name.hashCode();
      value.hashCode();
      headers.add(name);
      headers.add(value);
    }

    // TODO: lower concurrency in response to settings change as well
    int concurrentStreams = 0;
    while (true) {
      if (concurrentStreams < maxConcurrentStreams.get()) {
        concurrentStreams++;
        get(client, requests, errors, data, headers);
      } else {
        Thread.sleep(1000);
      }
    }
  }

  private static void get(final Http2Client client, final ProgressMeter.Metric requests,
                          final ProgressMeter.Metric errors, final ProgressMeter.Metric data,
                          final List<AsciiString> headers) {
    final long start = System.nanoTime();
    final Http2Request request = Http2Request.of(GET, PATH);
    for (int i = 0; i < headers.size(); i += 2) {
      request.header(headers.get(i), headers.get(i + 1));
    }
    client.send(request, new Http2ResponseHandler() {
      @Override
      public void response(final Http2Response response) {
        final long end = System.nanoTime();
        final long latency = end - start;
        requests.inc(latency);
        if (response.hasContent()) {
          data.add(response.content().readableBytes(), latency);
        }
        get(client, requests, errors, data, headers);
      }

      @Override
      public void failure(final Throwable e) {
        final long end = System.nanoTime();
        final long latency = end - start;
        errors.inc(latency);
        scheduler.schedule(() -> get(client, requests, errors, data, headers), 1, SECONDS);
      }
    });
  }
}
