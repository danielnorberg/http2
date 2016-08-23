package io.norberg.h2client.benchmarks;

import com.spotify.logging.LoggingConfigurator;

import io.netty.util.ResourceLeakDetector;

import static io.netty.util.ResourceLeakDetector.Level.DISABLED;

public class ReqRepBenchmark2 {

  public static void main(final String... args) throws Exception {
    LoggingConfigurator.configureNoLogging();
    ResourceLeakDetector.setLevel(DISABLED);
    BenchmarkServer2.run();
    BenchmarkClient2.run();
    while (true) {
      Thread.sleep(1000);
    }
  }
}
