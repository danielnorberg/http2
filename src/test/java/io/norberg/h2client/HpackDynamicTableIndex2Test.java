package io.norberg.h2client;

import org.junit.Test;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

public class HpackDynamicTableIndex2Test {

  private HpackDynamicTable table = new HpackDynamicTable();
  private HpackDynamicTableIndex2 index = new HpackDynamicTableIndex2(table);

  @Test
  public void insertLookup() throws Exception {
    Http2Header h1 = Http2Header.of("1", "1");
    table.addFirst(h1);
    index.insert(h1);
    final int ix = index.lookup(h1);
    assertThat(ix, is(1));
  }

}