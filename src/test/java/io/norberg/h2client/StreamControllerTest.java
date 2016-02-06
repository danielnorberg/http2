package io.norberg.h2client;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.runners.MockitoJUnitRunner;

import java.util.concurrent.ThreadLocalRandom;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import static io.netty.buffer.Unpooled.EMPTY_BUFFER;
import static io.norberg.h2client.Http2Protocol.DEFAULT_INITIAL_WINDOW_SIZE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class StreamControllerTest {

  interface Context {

  }

  private StreamController<Context, Stream> controller = new StreamController<>();

  @Mock Context ctx;
  @Mock StreamWriter<Context, Stream> writer;

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

  @Test
  public void testHappyPathSingleStream() throws Exception {
    testHappyPathSingleStream(1, 17);
  }

  private void testHappyPathSingleStream(final int id, final int size)
      throws io.netty.handler.codec.http2.Http2Exception {
    final int remoteInitialStreamWindowPre = controller.remoteInitialStreamWindow();
    final int remoteConnectionWindowPre = controller.remoteConnectionWindow();

    final ByteBuf data = randomByteBuf(size);
    final Stream stream = new Stream(id, data);
    controller.addStream(stream);

    // Verify that the stream remote window is not prematurely applied
    assertThat(stream.remoteWindow, is(0));

    // Start the outgoing stream
    controller.start(stream);

    // Verify that the stream remote window is applied when it is started
    assertThat(stream.remoteWindow, is(controller.remoteInitialStreamWindow()));

    final ByteBuf buf = Unpooled.buffer(4096);

    // Prepare the mock writer
    final InOrder inOrder = inOrder(writer);
    final int estimatedInitialHeadersFrameSize = 17;
    final int estimatedDataFrameSize = size + 31;
    final int expectedBufferSize = estimatedInitialHeadersFrameSize + estimatedDataFrameSize;
    when(writer.estimateInitialHeadersFrameSize(ctx, stream)).thenReturn(estimatedInitialHeadersFrameSize);
    when(writer.estimateDataFrameSize(ctx, stream, size)).thenReturn(estimatedDataFrameSize);
    when(writer.writeStart(eq(ctx), eq(expectedBufferSize))).thenReturn(buf);

    // Write the stream
    controller.flush(ctx, writer);

    // Verify the writer was correctly invoked
    inOrder.verify(writer).estimateInitialHeadersFrameSize(ctx, stream);
    inOrder.verify(writer).estimateDataFrameSize(ctx, stream, size);
    inOrder.verify(writer).writeStart(ctx, expectedBufferSize);
    inOrder.verify(writer).writeInitialHeadersFrame(ctx, buf, stream, false);
    inOrder.verify(writer).writeDataFrame(ctx, buf, stream, size, true);
    inOrder.verify(writer).writeEnd(ctx, buf);
    verifyNoMoreInteractions(writer);

    // Verify that the windows have been updated appropriately
    assertThat(controller.remoteConnectionWindow(), is(remoteConnectionWindowPre - size));
    assertThat(stream.remoteWindow, is(remoteInitialStreamWindowPre - size));
  }

  private ByteBuf randomByteBuf(final int size) {
    final byte[] bytes = new byte[size];
    ThreadLocalRandom.current().nextBytes(bytes);
    return Unpooled.wrappedBuffer(bytes);
  }

  @Test
  public void testHappyPathTwoConcurrentStreams() throws Exception {

    final int remoteInitialStreamWindowPre = controller.remoteInitialStreamWindow();
    final int remoteConnectionWindowPre = controller.remoteConnectionWindow();

    final ByteBuf data1 = Unpooled.copiedBuffer("hello 1", UTF_8);
    final int size1 = data1.readableBytes();
    final Stream stream1 = new Stream(1, data1);
    final ByteBuf data2 = Unpooled.copiedBuffer("hello world 2", UTF_8);
    final int size2 = data2.readableBytes();
    final Stream stream2 = new Stream(3, data2);

    controller.addStream(stream1);
    controller.addStream(stream2);

    // Verify that the stream remote window is not prematurely applied
    assertThat(stream1.remoteWindow, is(0));
    assertThat(stream2.remoteWindow, is(0));

    // Start the outgoing streams
    controller.start(stream1);
    controller.start(stream2);

    // Verify that the stream remote window is applied when it is started
    assertThat(stream1.remoteWindow, is(remoteInitialStreamWindowPre));
    assertThat(stream2.remoteWindow, is(remoteInitialStreamWindowPre));

    final ByteBuf buf = Unpooled.buffer(4096);

    // Prepare the mock writer
    final InOrder inOrder = inOrder(writer);
    final int estimatedInitialHeadersFrameSize1 = 17;
    final int estimatedDataFrameSize1 = size1 + 31;
    final int estimatedInitialHeadersFrameSize2 = 170;
    final int estimatedDataFrameSize2 = size1 + 310;
    final int expectedBufferSize1 = estimatedInitialHeadersFrameSize1 + estimatedDataFrameSize1;
    final int expectedBufferSize2 = estimatedInitialHeadersFrameSize2 + estimatedDataFrameSize2;
    final int expectedBufferSize = expectedBufferSize1 + expectedBufferSize2;
    when(writer.estimateInitialHeadersFrameSize(ctx, stream1)).thenReturn(estimatedInitialHeadersFrameSize1);
    when(writer.estimateDataFrameSize(ctx, stream1, size1)).thenReturn(estimatedDataFrameSize1);
    when(writer.estimateInitialHeadersFrameSize(ctx, stream2)).thenReturn(estimatedInitialHeadersFrameSize2);
    when(writer.estimateDataFrameSize(ctx, stream2, size2)).thenReturn(estimatedDataFrameSize2);
    when(writer.writeStart(eq(ctx), eq(expectedBufferSize))).thenReturn(buf);

    // Write the stream
    controller.flush(ctx, writer);

    // Verify the writer was correctly invoked
    inOrder.verify(writer).estimateInitialHeadersFrameSize(ctx, stream1);
    inOrder.verify(writer).estimateDataFrameSize(ctx, stream1, size1);
    inOrder.verify(writer).estimateInitialHeadersFrameSize(ctx, stream2);
    inOrder.verify(writer).estimateDataFrameSize(ctx, stream2, size2);
    inOrder.verify(writer).writeStart(ctx, expectedBufferSize);
    inOrder.verify(writer).writeInitialHeadersFrame(ctx, buf, stream1, false);
    inOrder.verify(writer).writeDataFrame(ctx, buf, stream1, size1, true);
    inOrder.verify(writer).writeInitialHeadersFrame(ctx, buf, stream2, false);
    inOrder.verify(writer).writeDataFrame(ctx, buf, stream2, size2, true);
    inOrder.verify(writer).writeEnd(ctx, buf);
    verifyNoMoreInteractions(writer);

    // Verify that the windows have been updated appropriately
    assertThat(controller.remoteConnectionWindow(), is(remoteConnectionWindowPre - size1 - size2));
    assertThat(stream1.remoteWindow, is(remoteInitialStreamWindowPre - size1));
    assertThat(stream2.remoteWindow, is(remoteInitialStreamWindowPre - size2));
  }

  @Test
  public void testHappyPathSequentialStreams() throws Exception {

    final int[] sizes = {17, 4711, 32, 45, 158};

    int expectedRemoteConnectionWindow = controller.remoteConnectionWindow();
    int id = 1;

    for (final int size : sizes) {
      testHappyPathSingleStream(id, size);
      expectedRemoteConnectionWindow -= size;
      assertThat(controller.remoteConnectionWindow(), is(expectedRemoteConnectionWindow));
      id += 2;
    }
  }

  @Test
  public void testStreamWindowExhaustionSingleStream() throws Exception {

    // Three frames:
    // Operation  | Data | Stream Window
    // ---------------------------------
    //            | 6    | 3
    // Write:  3  | 3    | 0
    // Update: 2  | 3    | 2
    // Write:  2  | 1    | 0
    // Update: 3  | 1    | 3
    // Write:  1  | 0    | 2

    final ByteBuf data = Unpooled.copiedBuffer("123456", UTF_8);
    final int size = 6;
    assertThat(data.readableBytes(), is(size));

    final int smallWindow = 3;

    final int initialConnectionWindow = controller.remoteConnectionWindow();

    controller.remoteInitialStreamWindowSizeUpdate(smallWindow);

    final Stream stream = new Stream(1, data);
    controller.addStream(stream);
    controller.start(stream);

    assertThat(stream.remoteWindow, is(smallWindow));

    // Prepare the mock writer
    final InOrder inOrder = inOrder(writer);
    final int estimatedInitialHeadersFrameSize = 100;
    final int estimatedDataFrame1Size = 10 + 3;
    final int estimatedDataFrame2Size = 10 + 2;
    final int estimatedDataFrame3Size = 10 + 1;
    final int expectedBuffer1Size = estimatedInitialHeadersFrameSize + estimatedDataFrame1Size;
    final int expectedBuffer2Size = estimatedDataFrame2Size;
    final int expectedBuffer3Size = estimatedDataFrame3Size;
    when(writer.estimateInitialHeadersFrameSize(ctx, stream)).thenReturn(estimatedInitialHeadersFrameSize);
    when(writer.estimateDataFrameSize(ctx, stream, 3)).thenReturn(estimatedDataFrame1Size);
    when(writer.estimateDataFrameSize(ctx, stream, 2)).thenReturn(estimatedDataFrame2Size);
    when(writer.estimateDataFrameSize(ctx, stream, 1)).thenReturn(estimatedDataFrame3Size);
    when(writer.writeStart(eq(ctx), eq(0))).thenReturn(EMPTY_BUFFER);

    // First write - exhaust stream window
    final ByteBuf buf1 = Unpooled.copyInt(1);
    when(writer.writeStart(eq(ctx), eq(expectedBuffer1Size))).thenReturn(buf1);
    controller.flush(ctx, writer);
    inOrder.verify(writer).estimateInitialHeadersFrameSize(ctx, stream);
    inOrder.verify(writer).estimateDataFrameSize(ctx, stream, 3);
    inOrder.verify(writer).writeStart(ctx, expectedBuffer1Size);
    inOrder.verify(writer).writeInitialHeadersFrame(ctx, buf1, stream, false);
    inOrder.verify(writer).writeDataFrame(ctx, buf1, stream, 3, false);
    inOrder.verify(writer).writeEnd(ctx, buf1);
    verifyNoMoreInteractions(writer);
    stream.data.skipBytes(3);

    // Verify that windows updated appropriately
    assertThat(controller.remoteConnectionWindow(), is(initialConnectionWindow - 3));
    assertThat(stream.remoteWindow, is(0));

    // Attempt to flush again, should not cause any writes
    controller.flush(ctx, writer);
    verifyNoMoreInteractions(writer);

    // Update the stream window
    controller.remoteStreamWindowUpdate(stream.id, 2);
    assertThat(stream.remoteWindow, is(2));

    // Second write - exhaust stream window again
    final ByteBuf buf2 = Unpooled.copyInt(2);
    when(writer.writeStart(eq(ctx), eq(expectedBuffer2Size))).thenReturn(buf2);
    controller.flush(ctx, writer);
    inOrder.verify(writer).estimateDataFrameSize(ctx, stream, 2);
    inOrder.verify(writer).writeStart(ctx, expectedBuffer2Size);
    inOrder.verify(writer).writeDataFrame(ctx, buf2, stream, 2, false);
    inOrder.verify(writer).writeEnd(ctx, buf2);
    verifyNoMoreInteractions(writer);
    stream.data.skipBytes(2);

    // Verify that windows updated appropriately
    assertThat(controller.remoteConnectionWindow(), is(initialConnectionWindow - 3 - 2));
    assertThat(stream.remoteWindow, is(0));

    // Attempt to flush again, should not cause any writes
    controller.flush(ctx, writer);
    verifyNoMoreInteractions(writer);

    // Update the stream window
    controller.remoteStreamWindowUpdate(stream.id, 3);
    assertThat(stream.remoteWindow, is(3));

    // Third and final write
    final ByteBuf buf3 = Unpooled.copyInt(3);
    when(writer.writeStart(eq(ctx), eq(expectedBuffer3Size))).thenReturn(buf3);
    controller.flush(ctx, writer);
    inOrder.verify(writer).estimateDataFrameSize(ctx, stream, 1);
    inOrder.verify(writer).writeStart(ctx, expectedBuffer3Size);
    inOrder.verify(writer).writeDataFrame(ctx, buf3, stream, 1, true);
    inOrder.verify(writer).writeEnd(ctx, buf3);
    verifyNoMoreInteractions(writer);
    stream.data.skipBytes(1);

    // Verify that windows updated appropriately
    assertThat(controller.remoteConnectionWindow(), is(initialConnectionWindow - 3 - 2 - 1));
    assertThat(stream.remoteWindow, is(2));

    // Attempt to flush again, should not cause any writes
    controller.flush(ctx, writer);
    verifyNoMoreInteractions(writer);
  }

  @Test
  public void testConnectionWindowExhaustionSingleStream() throws Exception {

    // First, consume all but 3 octets of the connection window
    final int ballastSize = controller.remoteConnectionWindow() - 3;
    final ByteBuf ballast = Unpooled.buffer(ballastSize);
    ballast.writerIndex(ballastSize);
    final Stream ballastStream = new Stream(1, ballast);
    when(writer.estimateInitialHeadersFrameSize(ctx, ballastStream)).thenReturn(4711);
    when(writer.estimateDataFrameSize(ctx, ballastStream, ballastSize)).thenReturn(32 + ballastSize);
    controller.addStream(ballastStream);
    controller.start(ballastStream);
    controller.flush(ctx, writer);
    assertThat(controller.remoteConnectionWindow(), is(3));
    reset(writer);

    // Three frames:
    // Operation  | Data | Connection Window
    // ----------------------------------
    //            | 6    | 3
    // Write:  3  | 3    | 0
    // Update: 2  | 3    | 2
    // Write:  2  | 1    | 0
    // Update: 3  | 1    | 3
    // Write:  1  | 0    | 2

    final ByteBuf data = Unpooled.copiedBuffer("123456", UTF_8);
    final int size = 6;
    assertThat(data.readableBytes(), is(size));

    final int remoteInitialStreamWindow = controller.remoteInitialStreamWindow();

    final Stream stream = new Stream(3, data);
    controller.addStream(stream);
    controller.start(stream);

    // Prepare the mock writer
    final InOrder inOrder = inOrder(writer);
    final int estimatedInitialHeadersFrameSize = 100;
    final int estimatedDataFrame1Size = 10 + 3;
    final int estimatedDataFrame2Size = 10 + 2;
    final int estimatedDataFrame3Size = 10 + 1;
    final int expectedBuffer1Size = estimatedInitialHeadersFrameSize + estimatedDataFrame1Size;
    final int expectedBuffer2Size = estimatedDataFrame2Size;
    final int expectedBuffer3Size = estimatedDataFrame3Size;
    when(writer.estimateInitialHeadersFrameSize(ctx, stream)).thenReturn(estimatedInitialHeadersFrameSize);
    when(writer.estimateDataFrameSize(ctx, stream, 3)).thenReturn(estimatedDataFrame1Size);
    when(writer.estimateDataFrameSize(ctx, stream, 2)).thenReturn(estimatedDataFrame2Size);
    when(writer.estimateDataFrameSize(ctx, stream, 1)).thenReturn(estimatedDataFrame3Size);

    // First write
    final ByteBuf buf1 = Unpooled.copyInt(1);
    when(writer.writeStart(eq(ctx), eq(expectedBuffer1Size))).thenReturn(buf1);
    controller.flush(ctx, writer);
    inOrder.verify(writer).estimateInitialHeadersFrameSize(ctx, stream);
    inOrder.verify(writer).estimateDataFrameSize(ctx, stream, 3);
    inOrder.verify(writer).writeStart(ctx, expectedBuffer1Size);
    inOrder.verify(writer).writeInitialHeadersFrame(ctx, buf1, stream, false);
    inOrder.verify(writer).writeDataFrame(ctx, buf1, stream, 3, false);
    inOrder.verify(writer).writeEnd(ctx, buf1);
    verifyNoMoreInteractions(writer);
    stream.data.skipBytes(3);

    // Verify that windows updated appropriately
    assertThat(controller.remoteConnectionWindow(), is(0));
    assertThat(stream.remoteWindow, is(remoteInitialStreamWindow - 3));

    // Attempt to flush again, should not cause any writes
    controller.flush(ctx, writer);
    verifyNoMoreInteractions(writer);

    // Update the connection window
    controller.remoteConnectionWindowUpdate(2);
    assertThat(controller.remoteConnectionWindow(), is(2));
    assertThat(stream.remoteWindow, is(remoteInitialStreamWindow - 3));

    // Second write
    final ByteBuf buf2 = Unpooled.copyInt(2);
    when(writer.writeStart(eq(ctx), eq(expectedBuffer2Size))).thenReturn(buf2);
    controller.flush(ctx, writer);
    inOrder.verify(writer).estimateDataFrameSize(ctx, stream, 2);
    inOrder.verify(writer).writeStart(ctx, expectedBuffer2Size);
    inOrder.verify(writer).writeDataFrame(ctx, buf2, stream, 2, false);
    inOrder.verify(writer).writeEnd(ctx, buf2);
    verifyNoMoreInteractions(writer);
    stream.data.skipBytes(2);

    // Verify that windows updated appropriately
    assertThat(controller.remoteConnectionWindow(), is(0));
    assertThat(stream.remoteWindow, is(remoteInitialStreamWindow - 3 - 2));

    // Attempt to flush again, should not cause any writes
    controller.flush(ctx, writer);
    verifyNoMoreInteractions(writer);

    // Update the connection window
    controller.remoteConnectionWindowUpdate(3);
    assertThat(controller.remoteConnectionWindow(), is(3));
    assertThat(stream.remoteWindow, is(remoteInitialStreamWindow - 3 - 2));

    // Third and final write
    final ByteBuf buf3 = Unpooled.copyInt(3);
    when(writer.writeStart(eq(ctx), eq(expectedBuffer3Size))).thenReturn(buf3);
    controller.flush(ctx, writer);
    inOrder.verify(writer).estimateDataFrameSize(ctx, stream, 1);
    inOrder.verify(writer).writeStart(ctx, expectedBuffer3Size);
    inOrder.verify(writer).writeDataFrame(ctx, buf3, stream, 1, true);
    inOrder.verify(writer).writeEnd(ctx, buf3);
    verifyNoMoreInteractions(writer);
    stream.data.skipBytes(1);

    // Verify that windows updated appropriately
    assertThat(controller.remoteConnectionWindow(), is(2));
    assertThat(stream.remoteWindow, is(remoteInitialStreamWindow - 3 - 2 - 1));

    // Attempt to flush again, should not cause any writes
    controller.flush(ctx, writer);
    verifyNoMoreInteractions(writer);
  }

  private static void reset(final Object mock) {
    Mockito.reset(mock);
  }

}