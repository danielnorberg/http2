package io.norberg.h2client.integration;

import org.junit.Test;

import java.util.concurrent.CompletableFuture;

import io.norberg.h2client.Http2Client;
import io.norberg.h2client.Http2Response;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

public class Http2ClientIT {

  @Test
  public void testGet() throws Exception {
    final Http2Client client = Http2Client.of("www.google.com");

    final long start1 = System.nanoTime();
    final CompletableFuture<Http2Response> indexFuture = client.get("/");
    final Http2Response indexResponse = indexFuture.get(30, SECONDS);
    final long end1 = System.nanoTime();
    final long latency1 = end1 - start1;
    System.out.println("INDEX RESPONSE HEADERS:");
    System.out.println(":status: " + indexResponse.status());
    indexResponse.forEachHeader((key, value) ->  System.out.println(key + "=" + value));
    System.out.println();
    indexResponse.release();

    final long start2 = System.nanoTime();
    final CompletableFuture<Http2Response> query1Future = client.get("/search?q=HTTP%2F2");
    final Http2Response query1Response = query1Future.get(30, SECONDS);
    final long end2 = System.nanoTime();
    final long latency2 = end2 - start2;
    System.out.println("QUERY1 RESPONSE HEADERS:");
    System.out.println(":status: " + indexResponse.status());
    indexResponse.forEachHeader((key, value) ->  System.out.println(key + "=" + value));
    System.out.println();
    query1Response.release();

    final long start3 = System.nanoTime();
    final CompletableFuture<Http2Response> query2Future = client.get("/search?q=HTTP%2F2");
    final Http2Response query2Response = query2Future.get(30, SECONDS);
    final long end3 = System.nanoTime();
    final long latency3 = end3 - start3;
    System.out.println("QUERY2 RESPONSE HEADERS:");
    System.out.println(":status: " + indexResponse.status());
    indexResponse.forEachHeader((key, value) ->  System.out.println(key + "=" + value));
    System.out.println();
    query2Response.release();

    System.out.printf("Request 1 latency: %d ms%n", NANOSECONDS.toMillis(latency1));
    System.out.printf("Request 2 latency: %d ms%n", NANOSECONDS.toMillis(latency2));
    System.out.printf("Request 3 latency: %d ms%n", NANOSECONDS.toMillis(latency3));
  }
}