package io.norberg.h2client.integration;

import com.googlecode.junittoolbox.ParallelRunner;
import com.spotify.logging.LoggingConfigurator;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.experimental.theories.Theory;
import org.junit.experimental.theories.suppliers.TestedOn;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import ch.qos.logback.classic.BasicConfigurator;
import io.netty.buffer.Unpooled;
import io.norberg.h2client.Http2Client;
import io.norberg.h2client.Http2Response;
import io.norberg.h2client.Http2Server;
import io.norberg.h2client.RequestHandler;

import static com.spotify.logging.LoggingConfigurator.Level.INFO;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.util.CharsetUtil.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.MockitoAnnotations.initMocks;

@Ignore
@RunWith(ParallelRunner.class)
public class Http2ClientServerIT {

  private final List<Http2Server> servers = new ArrayList<>();
  private final List<Http2Client> clients = new ArrayList<>();

  @Mock Http2Client.Listener listener;

  @BeforeClass
  public static void configureLogging() {
    BasicConfigurator.configureDefaultContext();
    LoggingConfigurator.configureDefaults("test", INFO);
  }

  @Before
  public void setUp() throws Exception {
    initMocks(this);
  }

  @After
  public void tearDown() throws Exception {
    servers.forEach(Http2Server::close);
    clients.forEach(Http2Client::close);
  }

  @Theory
  public void testReqRep(
      @TestedOn(ints = {1, 17, 4711, 65_535}) final int serverConnectionWindow,
      @TestedOn(ints = {1, 17, 4711, 65_535}) final int serverStreamWindow,
      @TestedOn(ints = {1, 17, 4711, 65_535}) final int clientConnectionWindow,
      @TestedOn(ints = {1, 17, 4711, 65_535}) final int clientStreamWindow) throws Exception {

    final RequestHandler requestHandler = (context, request) ->
        context.respond(request.response(
            OK, Unpooled.copiedBuffer("hello: " + request.path(), UTF_8)));

    // Start server
    final Http2Server server = autoClosing(
        Http2Server.builder()
            .requestHandler(requestHandler)
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

    final CompletableFuture<Http2Response> future = client.get("/world/1");
    final Http2Response response = future.get(30, SECONDS);
    final String payload = response.content().toString(UTF_8);
    assertThat(payload, is("hello: /world/1"));
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
