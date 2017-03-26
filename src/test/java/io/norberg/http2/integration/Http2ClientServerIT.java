package io.norberg.http2.integration;

import com.spotify.logging.LoggingConfigurator;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.experimental.theories.Theories;
import org.junit.experimental.theories.Theory;
import org.junit.experimental.theories.suppliers.TestedOn;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

import ch.qos.logback.classic.BasicConfigurator;
import io.netty.buffer.Unpooled;
import io.netty.util.ResourceLeakDetector;
import io.norberg.http2.Http2Client;
import io.norberg.http2.Http2Response;
import io.norberg.http2.Http2Server;

import static com.spotify.logging.LoggingConfigurator.Level.INFO;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.util.ResourceLeakDetector.Level.DISABLED;
import static java.util.concurrent.TimeUnit.MINUTES;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.MockitoAnnotations.initMocks;

@RunWith(Theories.class)
public class Http2ClientServerIT {

  private static final Logger log = LoggerFactory.getLogger(Http2ClientServerIT.class);

  private final List<Http2Server> servers = new ArrayList<>();
  private final List<Http2Client> clients = new ArrayList<>();

  @Mock Http2Client.Listener listener;

  @BeforeClass
  public static void configureEnvironment() {
    BasicConfigurator.configureDefaultContext();
    LoggingConfigurator.configureDefaults("test", INFO);
    ResourceLeakDetector.setLevel(DISABLED);
  }

  @Before
  public void setUp() throws Exception {
    initMocks(this);
  }

  @After
  public void tearDown() throws Exception {
    clients.forEach(Http2Client::close);
    servers.forEach(Http2Server::close);
  }

  @Theory
  public void testReqRep(
      @TestedOn(ints = {1, 17, 4711, 65_535}) final int serverConnectionWindow,
      @TestedOn(ints = {1, 17, 4711, 65_535}) final int serverStreamWindow,
      @TestedOn(ints = {1, 17, 4711, 65_535}) final int clientConnectionWindow,
      @TestedOn(ints = {1, 17, 4711, 65_535}) final int clientStreamWindow,
      @TestedOn(ints = {1, 17, 4711, 65_535}) final int payloadSize
  ) throws Exception {

    log.info("testReqRep: scw={} ssw={} ccw={} csw={} ps={}",
             serverConnectionWindow, serverStreamWindow, clientConnectionWindow, clientStreamWindow, payloadSize);

    final byte[] payload = new byte[payloadSize];
    ThreadLocalRandom.current().nextBytes(payload);

    // Start server
    final Http2Server server = autoClosing(
        Http2Server.builder()
            .requestHandler((context, request) ->
                context.respond(request.response(OK, Unpooled.wrappedBuffer(payload))))
            .connectionWindow(serverConnectionWindow)
            .streamWindow(serverStreamWindow)
            .build());
    final int port = server.bind(0).get().getPort();

    // Start client
    final Http2Client client = autoClosing(
        Http2Client.builder()
            .address("127.0.0.1", port)
            .connectionWindow(clientConnectionWindow)
            .streamWindow(clientStreamWindow)
            .build());

    final CompletableFuture<Http2Response> future = client.post("/hello/world", Unpooled.wrappedBuffer(payload));
    final Http2Response response = future.get(5, MINUTES);
    assertThat(response.content(), is(Unpooled.wrappedBuffer(payload)));
  }

  private Http2Server autoClosing(final Http2Server server) {
    servers.add(server);
    return server;
  }

  private Http2Client autoClosing(final Http2Client client) {
    clients.add(client);
    return client;
  }
}
