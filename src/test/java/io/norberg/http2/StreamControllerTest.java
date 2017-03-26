package io.norberg.http2;

import org.junit.Test;

import static io.norberg.http2.TestUtil.randomByteBuf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;

public class StreamControllerTest {

  private StreamController<Http2Stream> controller = new StreamController<>();

  @Test
  public void testNoStreams() throws Exception {
    assertThat(controller.streams(), is(0));
    assertThat(controller.stream(1), is(nullValue()));
    assertThat(controller.stream(4711), is(nullValue()));
    assertThat(controller.removeStream(1), is(nullValue()));
    assertThat(controller.removeStream(4711), is(nullValue()));
    assertThat(controller.streams(), is(0));
  }

  @Test
  public void testAddRemoveGetStream() throws Exception {
    final Http2Stream stream1 = new Http2Stream(1, randomByteBuf(17));

    // One stream
    controller.addStream(stream1);
    assertThat(controller.streams(), is(1));
    assertThat(controller.stream(1), is(stream1));
    assertThat(controller.stream(4711), is(nullValue()));
    assertThat(controller.removeStream(4711), is(nullValue()));
    assertThat(controller.streams(), is(1));

    // Two streams
    final Http2Stream stream2 = new Http2Stream(3, randomByteBuf(18));
    controller.addStream(stream2);
    assertThat(controller.streams(), is(2));
    assertThat(controller.stream(1), is(stream1));
    assertThat(controller.stream(3), is(stream2));
    assertThat(controller.stream(4711), is(nullValue()));
    assertThat(controller.removeStream(4711), is(nullValue()));
    assertThat(controller.streams(), is(2));

    // Remove first stream
    controller.removeStream(1);
    assertThat(controller.streams(), is(1));
    assertThat(controller.stream(1), is(nullValue()));
    assertThat(controller.stream(3), is(stream2));
  }
}