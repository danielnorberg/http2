package io.norberg.h2client;

import org.junit.Test;

import java.util.concurrent.CompletableFuture;

import io.netty.util.CharsetUtil;

import static java.util.concurrent.TimeUnit.SECONDS;

public class Http2ClientTest {

  @Test
  public void testGet() throws Exception {
    final Http2Client client = new Http2Client("www.google.com");
    final CompletableFuture<Http2Response> future = client.get("/");
    final Http2Response response = future.get(30, SECONDS);
    final String content = response.content().toString(CharsetUtil.UTF_8);
    response.release();
    System.out.println(content);
  }
}