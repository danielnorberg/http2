package io.norberg.h2client;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import io.norberg.h2client.benchmarks.ProgressMeter;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

public class HpackDynamicTableIndex2Test {

  private HpackDynamicTable table = new HpackDynamicTable();
  private HpackDynamicTableIndex2 index = new HpackDynamicTableIndex2(table);

  List<Http2Header> headers = new ArrayList<>();

  private final Random random = new Random(4711);

  private static final int N = 1024 * 128;
  private static final int MASK = N - 1;

  @Before
  public void setUp() throws Exception {
    for (int i = 0; i < N; i++) {
      headers.add(Http2Header.of(randomString(4, 16), randomString(4, 32)));
    }
  }

  private CharSequence randomString(final int min, final int max) {
    final int length = min + random.nextInt(max + 1 - min);
    return random.ints(length, (int) '!', (int) '~' + 1)
        .mapToObj((i) -> (char) i)
        .collect(StringBuilder::new, StringBuilder::append, StringBuilder::append)
        .toString();
  }

  @Test
  public void singleHeader() throws Exception {
    Http2Header header = Http2Header.of("foo", "bar");

    assertThat(index.lookup(header), is(-1));

    table.addFirst(header);
    index.insert(header);
    assertThat(index.lookup(header), is(0));

    table.removeLast();
    assertThat(index.lookup(header), is(-1));

    table.addFirst(header);
    index.insert(header);
    assertThat(index.lookup(header), is(0));
  }

  @Test
  public void headerStream() throws Exception {
    int n = 8;
    final ProgressMeter meter = new ProgressMeter();
    final ProgressMeter.Metric headerMetric = meter.group("throughput").metric("headers", "headers");
    int i = 0;
    while (true) {
      final long start = System.nanoTime();
      for (int j = 0; j < 1024; j++) {
        Http2Header header = headers.get(i);
        if (table.length() == n) {
          table.removeLast();
        }
        table.addFirst(header);
        index.insert(header);
        i = (i + 1) & MASK;
      }
      final long end = System.nanoTime();
      final long latency = end - start;
      headerMetric.add(1024, latency);
    }
  }
}