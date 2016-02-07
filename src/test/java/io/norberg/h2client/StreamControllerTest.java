package io.norberg.h2client;

import org.junit.Test;

import static io.norberg.h2client.TestUtil.randomByteBuf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;

public class StreamControllerTest {

  private StreamController<Stream> controller = new StreamController<>();

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
    final Stream stream1 = new Stream(1, randomByteBuf(17));

    // One stream
    controller.addStream(stream1);
    assertThat(controller.streams(), is(1));
    assertThat(controller.stream(1), is(stream1));
    assertThat(controller.stream(4711), is(nullValue()));
    assertThat(controller.removeStream(4711), is(nullValue()));
    assertThat(controller.streams(), is(1));

    // Two streams
    final Stream stream2 = new Stream(3, randomByteBuf(18));
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