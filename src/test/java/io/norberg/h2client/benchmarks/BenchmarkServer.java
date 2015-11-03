package io.norberg.h2client.benchmarks;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.norberg.h2client.Http2Server;
import io.norberg.h2client.RequestHandler;

import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

class BenchmarkServer {

  public static final int PAYLOAD_SIZE = 128;
  public static final ByteBuf[] PAYLOADS = BenchmarkUtil.payloads(PAYLOAD_SIZE, 1024);

  public static void main(final String... args) throws Exception {
    run();
    while (true) {
      Thread.sleep(1000);
    }
  }

  static void run() throws Exception {

    final RequestHandler requestHandler = (request) -> CompletableFuture.completedFuture(
        new DefaultFullHttpResponse(HTTP_1_1, OK, payload()));

    final Http2Server server = new Http2Server(requestHandler, 4711);

    server.bindFuture().get();

    System.out.println("Server listening on 0.0.0.0:" + server.port());
  }

  private static ByteBuf payload() {
    return PAYLOADS[ThreadLocalRandom.current().nextInt(PAYLOADS.length)].duplicate();
  }
}
