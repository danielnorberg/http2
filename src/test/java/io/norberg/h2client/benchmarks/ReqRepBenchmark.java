package io.norberg.h2client.benchmarks;

import com.spotify.logging.LoggingConfigurator;

import static com.spotify.logging.LoggingConfigurator.Level.DEBUG;

public class ReqRepBenchmark {

  public static void main(final String... args) throws Exception {
    LoggingConfigurator.configureDefaults("benchmark", DEBUG);
    BenchmarkServer.run();
    BenchmarkClient.run();
    while (true) {
      Thread.sleep(1000);
    }
  }
}
