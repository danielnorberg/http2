package io.norberg.http2;

import static io.norberg.http2.FlowControllerTest.FlushOp.Flags.END_OF_STREAM;
import static io.norberg.http2.Http2Protocol.DEFAULT_INITIAL_WINDOW_SIZE;
import static io.norberg.http2.TestUtil.randomByteBuf;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.AdditionalMatchers.geq;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableSet;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http2.Http2Exception;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class FlowControllerTest {

  interface Context {

  }

  private FlowController<Context, Http2Stream> controller = new FlowController<>();

  @Mock Context ctx;
  @Mock StreamWriter<Context, Http2Stream> writer;

  @Captor ArgumentCaptor<Integer> bufferSizeCaptor;

  @Test
  public void testRemoteConnectionWindowDefault() throws Exception {
    assertThat(controller.remoteConnectionWindow(), is(DEFAULT_INITIAL_WINDOW_SIZE));
  }

  @Test
  public void testRemoteInitialStreamWindowDefault() throws Exception {
    assertThat(controller.remoteInitialStreamWindow(), is(DEFAULT_INITIAL_WINDOW_SIZE));
  }

  @Test
  public void testConnectionWindowUpdate() throws Exception {
    controller.remoteConnectionWindowUpdate(4711);
    assertThat(controller.remoteConnectionWindow(), is(DEFAULT_INITIAL_WINDOW_SIZE + 4711));
  }

  @Test
  public void testStart() throws Exception {
    final Http2Stream stream = new Http2Stream(1, randomByteBuf(4711));
    assertThat(stream.started, is(false));
    controller.start(stream);
    assertThat(stream.started, is(true));
  }

  @Test
  public void testRemoteInitialStreamWindow() throws Exception {
    final int remoteInitialStreamWindow = controller.remoteInitialStreamWindow();

    final Http2Stream stream = new Http2Stream(17, randomByteBuf(4711));
    assertThat(stream.remoteWindow, is(0));

    controller.start(stream);

    // Verify that the stream remote window is applied when it is started
    assertThat(stream.remoteWindow, is(remoteInitialStreamWindow));
  }

  @Test
  public void testRemoteInitialStreamWindowUpdateIncrease() throws Exception {
    final int remoteInitialStreamWindow = controller.remoteInitialStreamWindow();
    final Http2Stream stream = new Http2Stream(17, randomByteBuf(4711));

    controller.start(stream);
    controller.remoteInitialStreamWindowSizeUpdate(remoteInitialStreamWindow + 47, asList(stream));

    // Verify that the stream remote window was updated
    assertThat(stream.remoteWindow, is(remoteInitialStreamWindow + 47));
  }

  @Test
  public void testRemoteInitialStreamWindowUpdateDecrease() throws Exception {
    final int remoteInitialStreamWindow = controller.remoteInitialStreamWindow();
    final Http2Stream stream = new Http2Stream(17, randomByteBuf(4711));

    controller.start(stream);
    controller.remoteInitialStreamWindowSizeUpdate(remoteInitialStreamWindow - 47, asList(stream));

    // Verify that the stream remote window was updated
    assertThat(stream.remoteWindow, is(remoteInitialStreamWindow - 47));
  }

  @Test
  public void testRemoteInitialStreamWindowDecreaseAfterWriteToNegativeStreamWindow() throws Exception {
    final int remoteConnectionWindow = 10;
    final int remoteInitialStreamWindow = 20;
    controller = new FlowController<>(remoteConnectionWindow, remoteInitialStreamWindow);

    final Http2Stream stream = new Http2Stream(17, randomByteBuf(4711));

    // Consume 10 octets of the stream window, leaving 10 octets
    controller.start(stream);
    verifyFlush(stream(stream).headers().estimate(10).write(10).pending(true));
    assertThat(stream.remoteWindow, is(10));

    // Lower the initial stream window by 15
    controller.remoteInitialStreamWindowSizeUpdate(remoteInitialStreamWindow - 15, asList(stream));

    // Verify that the stream remote window is now negative
    assertThat(stream.remoteWindow, is(-5));

    // Verify that a flush causes no writes
    verifyFlush();
  }

  @Test
  public void testRemoteInitialStreamWindowIncreaseAfterWriteToPositiveStreamWindow() throws Exception {
    final int remoteConnectionWindow = 20;
    final int remoteInitialStreamWindow = 10;
    controller = new FlowController<>(remoteConnectionWindow, remoteInitialStreamWindow);

    final Http2Stream stream = new Http2Stream(17, randomByteBuf(4711));

    // Consume 10 octets of the stream window, leaving 0 octets
    controller.start(stream);
    verifyFlush(stream(stream).headers().estimate(10).write(10).pending(false));
    assertThat(stream.remoteWindow, is(0));

    // Increase the initial stream window by 5
    controller.remoteInitialStreamWindowSizeUpdate(remoteInitialStreamWindow + 5, asList(stream));

    // Verify that the stream remote window is now positive
    assertThat(stream.remoteWindow, is(5));

    // Verify that a flush causes 5 octets to be written
    verifyFlush(stream(stream).estimate(5).write(5).pending(false));
  }

  @Test
  public void testRemoteInitialStreamWindowZero() throws Exception {
    final int remoteInitialStreamWindow = controller.remoteInitialStreamWindow();
    final Http2Stream stream = new Http2Stream(17, randomByteBuf(4711));

    controller.start(stream);
    controller.remoteInitialStreamWindowSizeUpdate(0, asList(stream));

    // Verify that the stream remote window was updated
    assertThat(stream.remoteWindow, is(0));
  }

  @Test
  public void testHappyPathSingleEmptyStream() throws Exception {
    final Http2Stream stream = startStream(1, 0);
    verifyFlush(stream(stream).headers(END_OF_STREAM));
  }

  @Test
  public void testHappyPathSingleStream() throws Exception {
    final Http2Stream stream = startStream(1, 17);
    verifyFlush(stream(stream).headers().estimate(17).write(17, END_OF_STREAM));
  }

  @Test
  public void testHappyPathSingleStreamMultipleFrames() throws Exception {
    controller.remoteMaxFrameSize(8);
    final Http2Stream stream = startStream(1, 17);
    verifyFlush(stream(stream).headers()
        .estimate(8).estimate(1)
        .write(8, 2).write(1, END_OF_STREAM));
  }

  @Test
  public void testHappyPathTwoConcurrentStreams() throws Exception {
    final int size1 = 7;
    final int size2 = 17;
    final Http2Stream stream1 = startStream(1, size1);
    final Http2Stream stream2 = startStream(3, size2);
    verifyFlush(
        stream(stream1).headers().estimate(size1).write(size1, END_OF_STREAM),
        stream(stream2).headers().estimate(size2).write(size2, END_OF_STREAM));
  }

  @Test
  public void testHappyPathSequentialStreams() throws Exception {

    final int[] sizes = {17, 4711, 32, 45, 158};

    int expectedRemoteConnectionWindow = controller.remoteConnectionWindow();
    int id = 1;

    for (final int size : sizes) {
      final Http2Stream stream = startStream(id, size);
      verifyFlush(stream(stream).headers().estimate(size).write(size, END_OF_STREAM));
      expectedRemoteConnectionWindow -= size;
      assertThat(controller.remoteConnectionWindow(), is(expectedRemoteConnectionWindow));
      id += 2;
    }
  }

  @Test
  public void testStreamWindowExhaustionSingleStream() throws Exception {

    controller.remoteInitialStreamWindowSizeUpdate(3, emptyList());

    // Three frames:
    // Operation  | Data | Stream Window
    // ---------------------------------
    //            | 6    | 3
    // Write:  3  | 3    | 0
    // Update: 2  | 3    | 2
    // Write:  2  | 1    | 0
    // Update: 3  | 1    | 3
    // Write:  1  | 0    | 2

    final Http2Stream stream = startStream(1, 6);
    verifyFlush(stream(stream).headers().estimate(3).write(3).pending(false));
    assertThat(stream.remoteWindow, is(0));
    verifyRemoteStreamWindowUpdate(2, stream);
    verifyFlush(stream(stream).estimate(2).write(2).pending(false));
    assertThat(stream.remoteWindow, is(0));
    verifyRemoteStreamWindowUpdate(3, stream);
    verifyFlush(stream(stream).estimate(1).write(1, END_OF_STREAM).pending(false));
    assertThat(stream.remoteWindow, is(2));
  }

  @Test
  public void testOneStreamWindowExhaustedStreamWithTwoHappyStreams() throws Exception {

    controller.remoteInitialStreamWindowSizeUpdate(10, emptyList());

    final Http2Stream exhausted = startStream(1, 20);
    verifyFlush(stream(exhausted).headers().estimate(10).write(10).pending(false));
    final Http2Stream happy1 = startStream(3, 5);
    final Http2Stream happy2 = startStream(5, 10);
    verifyFlush(
        stream(happy1).headers().estimate(5).write(5, END_OF_STREAM).pending(false),
        stream(happy2).headers().estimate(10).write(10, END_OF_STREAM).pending(false));
  }

  @Test
  public void testTwoStreamWindowExhaustedStreamsWithOneHappyStream() throws Exception {

    controller.remoteInitialStreamWindowSizeUpdate(10, emptyList());

    final Http2Stream exhausted1 = startStream(1, 20);
    final Http2Stream happy = startStream(3, 5);
    final Http2Stream exhausted2 = startStream(5, 30);
    verifyFlush(
        stream(exhausted1).headers().estimate(10).write(10).pending(false),
        stream(happy).headers().estimate(5).write(5, END_OF_STREAM).pending(false),
        stream(exhausted2).headers().estimate(10).write(10).pending(false));
  }

  @Test
  public void testStreamAndConnectionWindowExhaustedStreams() throws Exception {

    final int remoteConnectionWindow = 20;
    final int remoteInitialStreamWindow = 10;
    controller = new FlowController<>(remoteConnectionWindow, remoteInitialStreamWindow);

    // Exhaust the window of two streams and the connection window
    final Http2Stream streamWindowExhausted1 = startStream(1, 20);
    final Http2Stream streamWindowExhausted2 = startStream(3, 20);
    verifyFlush(
        stream(streamWindowExhausted1).headers().estimate(10).write(10).pending(false),
        stream(streamWindowExhausted2).headers().estimate(10).write(10).pending(false));
    assertThat(controller.remoteConnectionWindow(), is(0));

    // Update the connection window with enough for the first stream to complete
    verifyRemoteConnectionWindowUpdate(15, streamWindowExhausted1, streamWindowExhausted2);

    // Stream windows are still exhausted
    verifyFlush();

    // Feed stream windows
    verifyRemoteStreamWindowUpdate(5, streamWindowExhausted1);
    verifyFlush(stream(streamWindowExhausted1).estimate(5).write(5).pending(false));
    verifyRemoteStreamWindowUpdate(5, streamWindowExhausted2);
    verifyRemoteStreamWindowUpdate(5, streamWindowExhausted1);
    verifyFlush(
        stream(streamWindowExhausted2).estimate(5).write(5).pending(false),
        stream(streamWindowExhausted1).estimate(5).write(5, END_OF_STREAM).pending(false));

    assertThat(controller.remoteConnectionWindow(), is(0));
    assertThat(streamWindowExhausted2.remoteWindow, is(0));

    // Connection window is still exhausted
    verifyFlush();

    // Finish it
    verifyRemoteConnectionWindowUpdate(20, streamWindowExhausted2);

    // Stream window is still exhausted
    verifyFlush();

    // Feed stream window and finish stream
    verifyRemoteStreamWindowUpdate(10, streamWindowExhausted2);
    verifyFlush(stream(streamWindowExhausted2).estimate(5).write(5, END_OF_STREAM).pending(false));
  }

  @Test
  public void testConnectionWindowExhaustionSingleStream() throws Exception {

    controller = new FlowController<>(3, DEFAULT_INITIAL_WINDOW_SIZE);

    // Three frames:
    // Operation  | Data | Connection Window
    // ----------------------------------
    //            | 6    | 3
    // Write:  3  | 3    | 0
    // Update: 2  | 3    | 2
    // Write:  2  | 1    | 0
    // Update: 3  | 1    | 3
    // Write:  1  | 0    | 2

    final Http2Stream stream = startStream(1, 6);
    verifyFlush(stream(stream).headers().estimate(3).write(3).pending(true));
    verifyRemoteConnectionWindowUpdate(2, stream);
    verifyFlush(stream(stream).estimate(2).write(2).pending(true));
    verifyRemoteConnectionWindowUpdate(3, stream);
    verifyFlush(stream(stream).estimate(1).write(1, END_OF_STREAM).pending(false));
  }

  @Test
  public void testMultipleRemoteStreamWindowUpdates() throws Exception {
    controller = new FlowController<>(DEFAULT_INITIAL_WINDOW_SIZE, 7);

    final Http2Stream stream = startStream(1, 17);

    // Exhaust stream window
    verifyFlush(stream(stream).headers().estimate(7).write(7).pending(false));

    // Replenish stream window, twice
    verifyRemoteStreamWindowUpdate(3, stream);
    verifyRemoteStreamWindowUpdate(1024, stream);

    // Verify that stream is written once
    verifyFlush(stream(stream).estimate(10).write(10, END_OF_STREAM).pending(false));
  }

  @Test
  public void testRemoteStreamWindowUpdateBeforeFirstFlush() throws Exception {
    final Http2Stream stream = startStream(1, 17);
    verifyRemoteStreamWindowUpdate(3, stream);
    verifyFlush(stream(stream).headers().estimate(17).write(17, END_OF_STREAM).pending(false));
  }

  @Test
  public void testRemoteStreamWindowUpdateAndConnectionWindowUpdate() throws Exception {
    controller = new FlowController<>(7, DEFAULT_INITIAL_WINDOW_SIZE);

    final Http2Stream stream = startStream(1, 17);

    // Exhaust connection window
    verifyFlush(stream(stream).headers().estimate(7).write(7).pending(true));
    assertThat(controller.remoteConnectionWindow(), is(0));

    // Replenish stream window
    verifyRemoteStreamWindowUpdate(1024, stream);
    assertThat(stream.pending, is(true));

    // Replenish connection window
    verifyRemoteConnectionWindowUpdate(1024, stream);
    assertThat(stream.pending, is(true));

    // Verify that the stream is written once
    verifyFlush(stream(stream).estimate(10).write(10, END_OF_STREAM).pending(false));
  }

  @Test
  public void testConnectionWindowExhaustedDuringStreamWindowBlock() throws Exception {
    final int remoteConnectionWindow = 30;
    final int remoteInitialStreamWindow = 20;
    controller = new FlowController<>(remoteConnectionWindow, remoteInitialStreamWindow);

    // Exhaust the window of the first stream
    final Http2Stream stream1 = startStream(1, 40);
    verifyFlush(
        stream(stream1).headers().estimate(20).write(20).pending(false));

    // Then exhaust the connection window using a second stream
    final Http2Stream stream2 = startStream(3, 20);
    verifyFlush(
        stream(stream2).headers().estimate(10).write(10).pending(true));

    assertThat(controller.remoteConnectionWindow(), is(0));

    // Replenish the stream window of the first stream
    verifyRemoteStreamWindowUpdate(20, stream1);

    // Connection window is still exhausted
    verifyFlush();

    // And the first stream should still be pending
    assertThat(stream1.pending, is(true));

    // Replenish the connection window
    verifyRemoteConnectionWindowUpdate(40, stream1, stream2);

    // Now both streams should complete
    verifyFlush(
        stream(stream2).estimate(10).write(10, END_OF_STREAM).pending(false),
        stream(stream1).estimate(20).write(20, END_OF_STREAM).pending(false));
  }

  private Http2Stream startStream(final int id, final int size) {
    final ByteBuf data = randomByteBuf(size);
    final Http2Stream stream = new Http2Stream(id, data);
    controller.start(stream);
    return stream;
  }

  private void verifyRemoteStreamWindowUpdate(final int windowIncrease, final Http2Stream stream)
      throws Http2Exception {
    final int currentRemoteWindow = stream.remoteWindow;
    final int expectedRemoteWindow = currentRemoteWindow + windowIncrease;
    controller.remoteStreamWindowUpdate(stream, windowIncrease);
    assertThat(stream.remoteWindow, is(expectedRemoteWindow));
    assertThat(stream.pending, is(true));
  }

  private void verifyRemoteConnectionWindowUpdate(final int bytes, final Http2Stream... streams) throws Http2Exception {
    verifyRemoteConnectionWindowUpdate(bytes, asList(streams));
  }

  private void verifyRemoteConnectionWindowUpdate(final int bytes, final List<Http2Stream> streams)
      throws Http2Exception {
    // Record window sizes before update
    final int remoteConnectionWindowPre = controller.remoteConnectionWindow();
    final IdentityHashMap<Http2Stream, Integer> remoteStreamWindowsPre = new IdentityHashMap<>();
    final IdentityHashMap<Http2Stream, Boolean> pendingPre = new IdentityHashMap<>();
    for (final Http2Stream stream : streams) {
      remoteStreamWindowsPre.put(stream, stream.remoteWindow);
      pendingPre.put(stream, stream.pending);
    }

    // Update the connection window
    controller.remoteConnectionWindowUpdate(bytes);

    // Verify that the remote connection window was updated
    assertThat(controller.remoteConnectionWindow(), is(remoteConnectionWindowPre + bytes));

    // Verify that stream windows and pending status are unchanged
    for (final Http2Stream stream : streams) {
      assertThat(stream.remoteWindow, is(remoteStreamWindowsPre.get(stream)));
      assertThat(stream.pending, is(pendingPre.get(stream)));
    }
  }

  private void verifyFlush(final FlushOp... ops) throws Http2Exception {
    verifyFlush(asList(ops));
  }

  private void verifyFlush(final List<FlushOp> ops)
      throws Http2Exception {

    reset(writer);

    // Record window sizes before write
    final int remoteConnectionWindowPre = controller.remoteConnectionWindow();
    final IdentityHashMap<Http2Stream, Integer> remoteStreamWindowsPre = new IdentityHashMap<>();
    for (final FlushOp op : ops) {
      remoteStreamWindowsPre.put(op.stream, op.stream.remoteWindow);
    }

    // Prepare the mock writer
    int expectedMinBufferSize = 0;
    for (final FlushOp op : ops) {
      if (op.headers) {
        final int estimatedInitialHeadersFrameSize = ThreadLocalRandom.current().nextInt(32, 128);
        expectedMinBufferSize += estimatedInitialHeadersFrameSize;
        when(writer.estimateInitialHeadersFrameSize(ctx, op.stream)).thenReturn(estimatedInitialHeadersFrameSize);
      }
      for (final int estimate : op.estimates) {
        final int estimatedDataFrameSize = ThreadLocalRandom.current().nextInt(32, 128);
        expectedMinBufferSize += estimatedDataFrameSize;
        when(writer.estimateDataFrameSize(ctx, op.stream, estimate)).thenReturn(estimatedDataFrameSize);
      }
    }
    final ByteBuf buf = Unpooled.buffer(expectedMinBufferSize);
    when(writer.writeStart(eq(ctx), bufferSizeCaptor.capture()))
        .thenAnswer(i -> Unpooled.buffer(i.getArgument(1)));

    final InOrder inOrder = inOrder(writer);

    // Flush the streams
    controller.flush(ctx, writer);

    // Verify that header and data frame estimations come first, in stream order
    for (final FlushOp op : ops) {
      if (op.headers) {
        inOrder.verify(writer).estimateInitialHeadersFrameSize(ctx, op.stream);
      }
      for (final int estimate : op.estimates) {
        inOrder.verify(writer).estimateDataFrameSize(ctx, op.stream, estimate);
      }
    }

    // Verify that the write phase started if there is anything to write
    final int expectedWritesBytes = ops.stream()
        .mapToInt(op -> op.writes.stream()
            .mapToInt(w -> w.bytes * w.times)
            .sum())
        .sum();
    if (ops.size() > 0) {
      inOrder.verify(writer).writeStart(eq(ctx), geq(expectedMinBufferSize));
      final int bufferSize = bufferSizeCaptor.getValue();
      assertThat(bufferSize, is(greaterThanOrEqualTo(expectedMinBufferSize)));
    }

    // Verify expected data header and frame writes
    for (final FlushOp op : ops) {
      if (op.headers) {
        final boolean endOfStream = op.headerFlags.contains(END_OF_STREAM);
        inOrder.verify(writer).writeInitialHeadersFrame(ctx, buf, op.stream, endOfStream);
        if (endOfStream) {
          inOrder.verify(writer).streamEnd(op.stream);
        }
      }
      for (final FlushOp.Write write : op.writes) {
        final boolean endOfStream = write.flags.contains(END_OF_STREAM);
        inOrder.verify(writer, times(write.times)).writeDataFrame(ctx, buf, op.stream, write.bytes, endOfStream);
        if (endOfStream) {
          inOrder.verify(writer).streamEnd(op.stream);
        }
      }
    }

    // Verify that the write ended if there was anything to write
    if (ops.size() > 0) {
      inOrder.verify(writer).writeEnd(ctx, buf);
    }
    verifyNoMoreInteractions(writer);

    // Consume written bytes
    for (final FlushOp op : ops) {
      for (final FlushOp.Write write : op.writes) {
        op.stream.data.skipBytes(write.bytes * write.times);
      }
    }

    // Verify that the windows have been updated appropriately
    final int remoteConnectionWindowConsumption = expectedWritesBytes;
    assertThat(controller.remoteConnectionWindow(), is(remoteConnectionWindowPre - remoteConnectionWindowConsumption));
    for (final FlushOp op : ops) {
      final int streamWindowConsumption = op.writes.stream()
          .mapToInt(w -> w.bytes * w.times)
          .sum();
      final int expectedStreamWindow = remoteStreamWindowsPre.get(op.stream) - streamWindowConsumption;
      assertThat(op.stream.remoteWindow, is(expectedStreamWindow));
    }

    // Verify expected pending status of streams
    for (final FlushOp op : ops) {
      if (op.pending != null) {
        assertThat("unexpected stream " + op.stream.id + " pending status",
            op.stream.pending, is(op.pending));
      }
    }

    // Attempt to flush again, should not cause any writes
    controller.flush(ctx, writer);
    verifyNoMoreInteractions(writer);
  }

  private FlushOp stream(final Http2Stream stream) {
    return new FlushOp(stream);
  }

  static class FlushOp {

    static class Write {

      private final int bytes;
      private final int times;
      private Set<Flags> flags;

      Write(final int bytes, final int times, final Set<Flags> flags) {
        this.bytes = bytes;
        this.times = times;
        this.flags = flags;
      }
    }

    private final Http2Stream stream;
    private boolean headers;
    private Set<Flags> headerFlags;
    private List<Integer> estimates = new ArrayList<>();
    private List<Write> writes = new ArrayList<>();
    private Boolean pending;

    FlushOp(final Http2Stream stream) {
      this.stream = stream;
    }

    FlushOp headers(final Flags... flags) {
      return headers(true, flags);
    }

    FlushOp headers(final boolean headers, final Flags... flags) {
      this.headers = headers;
      this.headerFlags = ImmutableSet.copyOf(flags);
      return this;
    }

    FlushOp estimate(final int bytes) {
      this.estimates.add(bytes);
      return this;
    }

    FlushOp write(final int bytes, final int times, final Flags... flags) {
      this.writes.add(new Write(bytes, times, ImmutableSet.copyOf(flags)));
      return this;
    }

    FlushOp write(final int bytes, final Flags... flags) {
      this.writes.add(new Write(bytes, 1, ImmutableSet.copyOf(flags)));
      return this;
    }

    FlushOp pending(boolean pending) {
      this.pending = pending;
      return this;
    }

    enum Flags {
      END_OF_STREAM
    }
  }

  private static void reset(Object... w) {
    Mockito.reset(w);
  }
}