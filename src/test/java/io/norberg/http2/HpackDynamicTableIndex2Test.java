package io.norberg.http2;

import org.junit.Ignore;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

import io.norberg.http2.benchmarks.ProgressMeter;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

public class HpackDynamicTableIndex2Test {

  private HpackDynamicTable table = new HpackDynamicTable();
  private HpackDynamicTableIndex4 index = new HpackDynamicTableIndex4(table);

  @Test
  public void singleHeader() throws Exception {
    Http2Header header = Http2Header.of("foo", "bar");

    assertThat(index.lookup(header), is(0));
    assertThat(index.lookup(header.name()), is(0));

    table.addFirst(header);
    index.insert(header);
    assertThat(index.lookup(header), is(1));
    assertThat(index.lookup(header.name()), is(1));

    int ix = table.length();
    final Http2Header removed = table.removeLast();
    index.remove(removed);
    assertThat(index.lookup(header), is(0));
    assertThat(index.lookup(header.name()), is(0));

    table.addFirst(header);
    index.insert(header);
    assertThat(index.lookup(header), is(1));
    assertThat(index.lookup(header.name()), is(1));
  }

  @Test
  public void headerStream() throws Exception {
    final int n = 128;
    final Random r = new Random(17);
    Map<Http2Header, Integer> expectedHeaderIndices = new HashMap<>();
    Map<CharSequence, Integer> expectedNameIndices = new HashMap<>();
    for (int i = 0; i < 16 * 1024; i++) {
      Http2Header header;
      if (r.nextInt(10) == 0) {
        final int index = r.nextInt(table.length());
        header = table.header(index);
      } else {
        header = Http2Header.of("name-" + i, "value-" + i);
      }
      final Http2Header removed;
      if (table.length() == n) {
        int ix = table.length();
        removed = table.removeLast();
        index.remove(removed);
      } else {
        removed = null;
      }
      table.addFirst(header);
      index.insert(header);
      index.validate();
      expectedHeaderIndices.clear();
      expectedNameIndices.clear();
      for (int j = table.length() - 1; j >= 0; j--) {
        final Http2Header h = table.header(j);
        expectedHeaderIndices.put(h, j + 1);
        expectedNameIndices.put(h.name(), j + 1);
      }
      if (removed != null) {
        if (!expectedHeaderIndices.containsKey(removed)) {
          assertThat(index.lookup(removed), is(0));
        }
        if (!expectedNameIndices.containsKey(removed.name())) {
          assertThat(index.lookup(removed.name()), is(0));
        }
      }
      for (int j = 0; j < table.length(); j++) {
        final Http2Header h = table.header(j);
        if (!Objects.equals(index.lookup(h), expectedHeaderIndices.get(h))) {
          throw new AssertionError(h + ": " + index.lookup(h) + " != " + expectedHeaderIndices.get(h));
        }
        assertThat(index.lookup(h), is(expectedHeaderIndices.get(h)));
        assertThat(index.lookup(h.name()), is(expectedNameIndices.get(h.name())));
      }
    }
  }

  @Test
  public void growth() throws Exception {
    for (int i = 0; i < 1024; i++) {
      Http2Header header = Http2Header.of("name-" + i, "value-" + i);
      table.addFirst(header);
      index.insert(header);
      index.validate();
      for (int j = 0; j < table.length(); j++) {
        final Http2Header h = table.header(j);
        assertThat(index.lookup(h), is(j + 1));
        assertThat(index.lookup(h.name()), is(j + 1));
      }
    }
  }

  @Ignore("this is a benchmark")
  @Test
  public void benchmark() throws Exception {
    final List<Http2Header> headers = new ArrayList<>();
    final int N = 1024 * 128;
    final int MASK = N - 1;

    final Random r = ThreadLocalRandom.current();
    for (int i = 0; i < N; i++) {
      headers.add(Http2Header.of(randomString(r, 4, 16), randomString(r, 4, 32)));
    }

    int hits = 0;
    final int batch = 16 * 1024;
    final int n = 128;
    final ProgressMeter meter = new ProgressMeter();
    final ProgressMeter.Metric headerMetric = meter.group("throughput").metric("headers", "headers");
    int i = 0;
    for (int j = 0; j < Integer.MAX_VALUE; j++) {
      final long start = System.nanoTime();
      for (int k = 0; k < batch; k++) {
        Http2Header header = headers.get(i);
        if (index.lookup(header) != -1) {
          hits++;
        }
        if (index.lookup(header.name()) != -1) {
          hits++;
        }
        if (table.length() == n) {
          int ix = table.length();
          final Http2Header removed = table.removeLast();
          index.remove(removed);
        }
        table.addFirst(header);
        index.insert(header);
        i = (i + 1) & MASK;
      }
      final long end = System.nanoTime();
      final long latency = end - start;
      headerMetric.add(batch, latency);
    }
    System.out.println(hits);
  }

  private static CharSequence randomString(Random random, final int min, final int max) {
    final int length = min + random.nextInt(max + 1 - min);
    return random.ints(length, (int) '!', (int) '~' + 1)
        .mapToObj((i) -> (char) i)
        .collect(StringBuilder::new, StringBuilder::append, StringBuilder::append)
        .toString();
  }
}