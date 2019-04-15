package io.norberg.http2.benchmarks;

import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.util.ResourceLeakDetector.Level.DISABLED;

import com.spotify.logging.LoggingConfigurator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.util.AsciiString;
import io.netty.util.ResourceLeakDetector;
import io.norberg.http2.Http2Response;
import io.norberg.http2.Http2Server;
import io.norberg.http2.RequestHandler;
import io.norberg.http2.RequestStreamHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

class StreamingBenchmarkServer {

  private static final int PAYLOAD_SIZE = 128;
  private static final byte[][] ARRAY_PAYLOADS = BenchmarkUtil.arrayPayloads(PAYLOAD_SIZE, 1024);

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
    final int numHeaders = 16;
    for (int i = 0; i < numHeaders; i++) {
      final AsciiString name = AsciiString.of("header" + i);
      final AsciiString value = AsciiString.of("value" + i);
      name.hashCode();
      value.hashCode();
      headers.add(name);
      headers.add(value);
    }

    final RequestHandler requestHandler = stream -> new RequestStreamHandler() {

      int size = 0;

      {
        requests.inc(0);
      }

      @Override
      public void method(HttpMethod method) {
        size += method.asciiName().length();
      }

      @Override
      public void scheme(AsciiString scheme) {
        size += scheme.length();
      }

      @Override
      public void authority(AsciiString authority) {
        size += authority.length();
      }

      @Override
      public void path(AsciiString path) {
        size += path.length();
      }

      @Override
      public void header(AsciiString name, AsciiString value) {
        size += name.length() + value.length();
      }

      @Override
      public void data(ByteBuf data, int padding) {
        size += data.readableBytes();
      }

      @Override
      public void trailer(AsciiString name, AsciiString value) {
        size += name.length() + value.length();
      }

      @Override
      public void end() {
        data.add(size, 0);
        final Http2Response response = Http2Response.of(OK);
        for (int i = 0; i < headers.size(); i += 2) {
          response.header(headers.get(i), headers.get(i + 1));
        }
        stream.send(response);
        stream.end(payload());
      }
    };

    final Http2Server server = Http2Server.builder()
        .requestHandler(requestHandler)
        .connectionWindow(1024 * 1024)
        .build();
    final int port = server.bind(4711).get().getPort();

    System.out.println("Server listening on 0.0.0.0:" + port);
  }

  private static ByteBuf payload() {
    return Unpooled.wrappedBuffer(ARRAY_PAYLOADS[ThreadLocalRandom.current().nextInt(ARRAY_PAYLOADS.length)]);
  }
}
