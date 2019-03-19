package io.norberg.http2.benchmarks;

import static io.netty.util.ResourceLeakDetector.Level.DISABLED;

import com.spotify.logging.LoggingConfigurator;
import com.spotify.spawn.Subprocess;
import com.spotify.spawn.Subprocesses;
import io.netty.util.ResourceLeakDetector;

public class ReqRepBenchmark {

  public static void main(final String... args) throws Exception {
    LoggingConfigurator.configureNoLogging();
    ResourceLeakDetector.setLevel(DISABLED);

    final BenchmarkServer server = BenchmarkServer.start(0);

    final Subprocess clientProcess = Subprocesses.process().main(BenchmarkClient.class)
        .args("127.0.0.1", String.valueOf(server.port))
        .inheritIO()
        .spawn();

    clientProcess.join();

    server.server.close();
  }
}
