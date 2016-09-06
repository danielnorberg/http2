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
  private static final int PROGRESS_N = 1024;
  private static final int PROGRESS_MASK = PROGRESS_N - 1;
  private static final int MASK = N - 1;

  @Before
  public void setUp() throws Exception {
    for (int i = 0; i < N; i++) {
      headers.add(Http2Header.of(randomString(4,16), randomString(4,32)));
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
  public void foo() throws Exception {
//    System.out.println(HpackDynamicTableIndex2.mix(0xf5a5009c) & 15);
//    System.out.println(HpackDynamicTableIndex2.mix(0x15a5009c) & 15);

    System.out.printf("%08x%n", HpackDynamicTableIndex2.hash(Http2Header.of("foo0", "bar0")) & 15);
    System.out.printf("%08x%n", HpackDynamicTableIndex2.hash(Http2Header.of("foo1", "bar1")) & 15);
    System.out.printf("%08x%n", HpackDynamicTableIndex2.hash(Http2Header.of("foo2", "bar2")) & 15);
  }

  int x = 17;
  int y = 4711;
  int z = 17 * 4711;
  int w = 31;

  int xorshift128() {
    int t = x;
    t ^= t << 11;
    t ^= t >>> 8;
    x = y;
    y = z;
    z = w;
    w ^= w >>> 19;
    w ^= t;
    return w;
  }

  @Test
  public void headerStream() throws Exception {
    int n = 8;
//    int[] hist = new int[16];
//    for (int i = 0; i < 1024 * 1024 * 1024; i++) {
    final ProgressMeter meter = new ProgressMeter();
    final ProgressMeter.Metric headerMetric = meter.group("throughput").metric("headers", "headers");
    int i = 0;
    long start = System.nanoTime();
    while (true) {

      Http2Header header = headers.get(i);

//      int s = xorshift128();
//      int s = i;
//      String name = "foo" + Integer.toHexString(s);
//      String value = "bar" + Integer.toHexString(s);
//      Http2Header header = Http2Header.of(name, value);



//      int ix = HpackDynamicTableIndex2.hash(header) & 15;
//      System.out.printf("%6s: %08x %08x, %6s: %08x %08x, %08x (%08x) %n",
//          header.name(), header.name().hashCode(), header.name().toString().hashCode(),
//          header.value(), header.value().hashCode(), header.value().toString().hashCode(),
//          header.hashCode(), ix);
//      hist[ix]++;

//      System.out.println(header.name() + ": " + Integer.toHexString(header.name().hashCode());
//      System.out.println(header.value() + ": " + Integer.toHexString(header.value().hashCode()));
//      System.out.println(header + ": " + Integer.toHexString(header.hashCode()));

//      assertThat(index.lookup(header), is(-1));
      if (table.length() == n) {
        table.removeLast();
      }
      table.addFirst(header);
//      if (i == 10) {
//        System.out.printf("foo!");
//      }
      index.insert(header);
//      index.validateInvariants();

      if ((i & PROGRESS_MASK) == 0) {
        long end = System.nanoTime();
        long latency = end - start;
        start = end;
        headerMetric.add(PROGRESS_N, latency);
      }
      i = (i + 1) & MASK;

//      for (int j = 0; j < table.length(); j++) {
//        Http2Header tableHeader = table.header(j);
//        if (index.lookup(tableHeader) != j) {
//          index.validateInvariants();
//          index.lookup(tableHeader);
//          fail();
//        }
////        assertThat(index.lookup(tableHeader), is(j));
//      }

//      System.out.println(Arrays.toString(index.probeDistances()));
    }

//    System.out.println(Arrays.toString(hist));
  }
}