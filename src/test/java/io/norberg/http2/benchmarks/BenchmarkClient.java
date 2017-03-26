package io.norberg.http2.benchmarks;

import com.spotify.logging.LoggingConfigurator;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.util.AsciiString;
import io.netty.util.ResourceLeakDetector;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.norberg.http2.Http2Client;
import io.norberg.http2.Http2Request;
import io.norberg.http2.Http2Response;
import io.norberg.http2.Http2ResponseHandler;

import static io.netty.handler.codec.http.HttpMethod.POST;
import static io.netty.util.ResourceLeakDetector.Level.DISABLED;
import static java.util.concurrent.TimeUnit.SECONDS;

class BenchmarkClient {

  private static final int PAYLOAD_SIZE = 128;
  private static final ByteBuf[] PAYLOADS = BenchmarkUtil.payloads(PAYLOAD_SIZE, 1024);
  private static final byte[][] ARRAY_PAYLOADS = BenchmarkUtil.arrayPayloads(PAYLOAD_SIZE, 1024);

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
    final int numHeaders = 16;
    for (int i = 0; i < numHeaders; i++) {
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
        post(client, requests, errors, data, headers);
      } else {
        Thread.sleep(1000);
      }
    }
  }

  private static void post(final Http2Client client, final ProgressMeter.Metric requests,
                           final ProgressMeter.Metric errors, final ProgressMeter.Metric data,
                           final List<AsciiString> headers) {
    final long start = System.nanoTime();
    final Http2Request request = Http2Request.of(POST, PATH, payload());
    for (int i = 0; i < headers.size(); i += 2) {
      request.header(headers.get(i), headers.get(i + 1));
    }
    client.send(request, new Http2ResponseHandler() {
      @Override
      public void response(final Http2Response response) {
        final long end = System.nanoTime();
        final long latency = end - start;
        requests.inc(latency);
        int size = 0;
        if (response.hasContent()) {
          size += response.content().readableBytes();
        }
        if (response.hasHeaders()) {
          for (int i = 0; i < response.numHeaders(); i++) {
            size += response.headerName(i).length() + response.headerValue(i).length();
          }
        }
        data.add(size, 0);
        response.release();
        post(client, requests, errors, data, headers);
      }

      @Override
      public void failure(final Throwable e) {
        final long end = System.nanoTime();
        final long latency = end - start;
        errors.inc(latency);
        scheduler.schedule(() -> post(client, requests, errors, data, headers), 1, SECONDS);
      }
    });
  }

  private static ByteBuf payload() {
    return Unpooled.wrappedBuffer(ARRAY_PAYLOADS[ThreadLocalRandom.current().nextInt(ARRAY_PAYLOADS.length)]);
//    return PAYLOADS[ThreadLocalRandom.current().nextInt(PAYLOADS.length)].duplicate();
  }
}
