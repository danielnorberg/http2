package io.norberg.http2.benchmarks;

import com.spotify.logging.LoggingConfigurator;

import io.netty.util.ResourceLeakDetector;

import static io.netty.util.ResourceLeakDetector.Level.DISABLED;

public class ReqRepBenchmark {

  public static void main(final String... args) throws Exception {
    LoggingConfigurator.configureNoLogging();
    ResourceLeakDetector.setLevel(DISABLED);
    BenchmarkServer.run();
    BenchmarkClient.run();
    while (true) {
      Thread.sleep(1000);
    }
  }
}
