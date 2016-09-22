package io.norberg.h2client.benchmarks;

import com.google.common.base.Strings;

import com.spotify.logging.LoggingConfigurator;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.AsciiString;
import io.netty.util.ResourceLeakDetector;
import io.norberg.h2client.Http2Response;
import io.norberg.h2client.Http2Server;
import io.norberg.h2client.RequestHandler;

import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.util.ResourceLeakDetector.Level.DISABLED;
import static java.nio.charset.StandardCharsets.UTF_8;

class BenchmarkServer {

  public static final int PAYLOAD_SIZE = 128;
  public static final ByteBuf[] PAYLOADS = BenchmarkUtil.payloads(PAYLOAD_SIZE, 1024);

  private static final ByteBuf HELLO_WORLD = Unpooled.unreleasableBuffer(
      Unpooled.copiedBuffer(Strings.repeat("hello world ", 256), UTF_8));

  public static void main(final String... args) throws Exception {
    LoggingConfigurator.configureNoLogging();
    ResourceLeakDetector.setLevel(DISABLED);
    run();
    while (true) {
      Thread.sleep(1000);
    }
  }

  static void run() throws Exception {

    final ProgressMeter meter = new ProgressMeter();
    final ProgressMeter.Metric requests = meter.group("throughput").metric("requests", "requests");
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

    final RequestHandler requestHandler = (context, request) -> {
      requests.inc(0);
      if (request.hasContent()) {
        data.add(request.content().readableBytes(), 0);
      }
      final Http2Response response = request.response(OK);
      for (int i = 0; i < headers.size(); i += 2) {
        response.header(headers.get(i), headers.get(i + 1));
      }
      context.respond(response);
      request.release();
    };

    final Http2Server server = Http2Server.builder()
        .requestHandler(requestHandler)
        .connectionWindow(1024 * 1024)
        .build();
    final int port = server.bind(4711).get().getPort();

    System.out.println("Server listening on 0.0.0.0:" + port);
  }

  private static ByteBuf payload() {
    return PAYLOADS[ThreadLocalRandom.current().nextInt(PAYLOADS.length)].duplicate();
  }
}
