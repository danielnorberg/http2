package io.norberg.h2client;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.runners.MockitoJUnitRunner;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import static io.netty.buffer.Unpooled.EMPTY_BUFFER;
import static io.norberg.h2client.Http2Protocol.DEFAULT_INITIAL_WINDOW_SIZE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.verify;
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
  public void testConnectionWindowDefault() throws Exception {
    assertThat(controller.remoteConnectionWindow(), is(DEFAULT_INITIAL_WINDOW_SIZE));
  }

  @Test
  public void testConnectionWindowUpdate() throws Exception {
    controller.remoteConnectionWindowUpdate(4711);
    assertThat(controller.remoteConnectionWindow(), is(DEFAULT_INITIAL_WINDOW_SIZE + 4711));
  }

  @Test
  public void testHappyPath() throws Exception {

    final ByteBuf data = Unpooled.copiedBuffer("hello world", UTF_8);
    final int size = data.readableBytes();
    final Stream stream = new Stream(3, data);
    controller.addStream(stream);

    // Verify that the stream remote window is not prematurely applied
    assertThat(stream.remoteWindow, is(0));

    // Start the outgoing stream
    controller.start(stream);

    // Verify that the stream remote window is applied when it is started
    assertThat(stream.remoteWindow, is(controller.remoteInitialStreamWindow()));

    final ByteBuf buf = Unpooled.buffer(4096);

    // Prepare the mock writer
    final int estimatedInitialHeadersFrameSize = 17;
    final int estimatedDataFrameSize = size + 31;
    final int expectedBufferSize = estimatedInitialHeadersFrameSize + estimatedDataFrameSize;
    when(writer.estimateInitialHeadersFrameSize(ctx, stream)).thenReturn(estimatedInitialHeadersFrameSize);
    when(writer.estimateDataFrameSize(ctx, stream, size)).thenReturn(estimatedDataFrameSize);
    when(writer.writeStart(eq(ctx), anyInt())).thenReturn(buf);

    // Write the stream
    controller.flush(ctx, writer);

    // Verify the writer was correctly invoked
    verify(writer).estimateInitialHeadersFrameSize(ctx, stream);
    verify(writer).estimateDataFrameSize(ctx, stream, size);
    verify(writer).writeStart(ctx, expectedBufferSize);
    verify(writer).writeInitialHeadersFrame(ctx, buf, stream, false);
    verify(writer).writeDataFrame(ctx, buf, stream, size, true);
    verify(writer).writeEnd(ctx, buf);

    // Verify that the windows have been updated appropriately
    assertThat(controller.remoteConnectionWindow(), is(DEFAULT_INITIAL_WINDOW_SIZE - size));
    assertThat(stream.remoteWindow, is(DEFAULT_INITIAL_WINDOW_SIZE - size));
  }

  @Test
  public void testStreamWindowing() throws Exception {

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

    final Stream stream = new Stream(3, data);
    controller.addStream(stream);
    controller.start(stream);

    assertThat(stream.remoteWindow, is(smallWindow));

    // Prepare the mock writer
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
    when(writer.writeStart(eq(ctx), anyInt())).thenReturn(buf1);
    controller.flush(ctx, writer);
    verify(writer).estimateInitialHeadersFrameSize(ctx, stream);
    verify(writer).estimateDataFrameSize(ctx, stream, 3);
    verify(writer).writeStart(ctx, expectedBuffer1Size);
    verify(writer).writeInitialHeadersFrame(ctx, buf1, stream, false);
    verify(writer).writeDataFrame(ctx, buf1, stream, 3, false);
    verify(writer).writeEnd(ctx, buf1);
    stream.data.skipBytes(3);

    // Verify that windows updated appropriately
    assertThat(controller.remoteConnectionWindow(), is(initialConnectionWindow - 3));
    assertThat(stream.remoteWindow, is(0));

    // Attempt to flush again, should not cause any writes
    controller.flush(ctx, writer);
    verifyNoMoreInteractions(writer);

    // Update the stream window
    controller.remoteStreamWindowUpdate(3, 2);
    assertThat(stream.remoteWindow, is(2));

    // Second write - exhaust stream window again
    final ByteBuf buf2 = Unpooled.copyInt(2);
    when(writer.writeStart(eq(ctx), anyInt())).thenReturn(buf2);
    controller.flush(ctx, writer);
    verify(writer).estimateDataFrameSize(ctx, stream, 2);
    verify(writer).writeStart(ctx, expectedBuffer2Size);
    verify(writer).writeDataFrame(ctx, buf2, stream, 2, false);
    verify(writer).writeEnd(ctx, buf2);
    stream.data.skipBytes(2);

    // Verify that windows updated appropriately
    assertThat(controller.remoteConnectionWindow(), is(initialConnectionWindow - 3 - 2));
    assertThat(stream.remoteWindow, is(0));

    // Attempt to flush again, should not cause any writes
    controller.flush(ctx, writer);
    verifyNoMoreInteractions(writer);

    // Update the stream window
    controller.remoteStreamWindowUpdate(3, 3);
    assertThat(stream.remoteWindow, is(3));

    // Third and final write
    final ByteBuf buf3 = Unpooled.copyInt(3);
    when(writer.writeStart(eq(ctx), anyInt())).thenReturn(buf3);
    controller.flush(ctx, writer);
    verify(writer).estimateDataFrameSize(ctx, stream, 1);
    verify(writer).writeStart(ctx, expectedBuffer3Size);
    verify(writer).writeDataFrame(ctx, buf3, stream, 1, true);
    verify(writer).writeEnd(ctx, buf3);
    stream.data.skipBytes(1);

    // Verify that windows updated appropriately
    assertThat(controller.remoteConnectionWindow(), is(initialConnectionWindow - 3 - 2 - 1));
    assertThat(stream.remoteWindow, is(2));

    // Attempt to flush again, should not cause any writes
    controller.flush(ctx, writer);
    verifyNoMoreInteractions(writer);
  }

  @Test
  public void testConnectionWindowing() throws Exception {

    // First, consume all but 3 octets of the connection window
    final int ballastSize = controller.remoteConnectionWindow() - 3;
    final ByteBuf ballast = Unpooled.buffer(ballastSize);
    ballast.writerIndex(ballastSize);
    final Stream ballastStream = new Stream(3, ballast);
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

    final Stream stream = new Stream(5, data);
    controller.addStream(stream);
    controller.start(stream);

    // Prepare the mock writer
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
    when(writer.writeStart(eq(ctx), anyInt())).thenReturn(buf1);
    controller.flush(ctx, writer);
    verify(writer).estimateInitialHeadersFrameSize(ctx, stream);
    verify(writer).estimateDataFrameSize(ctx, stream, 3);
    verify(writer).writeStart(ctx, expectedBuffer1Size);
    verify(writer).writeInitialHeadersFrame(ctx, buf1, stream, false);
    verify(writer).writeDataFrame(ctx, buf1, stream, 3, false);
    verify(writer).writeEnd(ctx, buf1);
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
    when(writer.writeStart(eq(ctx), anyInt())).thenReturn(buf2);
    controller.flush(ctx, writer);
    verify(writer).estimateDataFrameSize(ctx, stream, 2);
    verify(writer).writeStart(ctx, expectedBuffer2Size);
    verify(writer).writeDataFrame(ctx, buf2, stream, 2, false);
    verify(writer).writeEnd(ctx, buf2);
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
    when(writer.writeStart(eq(ctx), anyInt())).thenReturn(buf3);
    controller.flush(ctx, writer);
    verify(writer).estimateDataFrameSize(ctx, stream, 1);
    verify(writer).writeStart(ctx, expectedBuffer3Size);
    verify(writer).writeDataFrame(ctx, buf3, stream, 1, true);
    verify(writer).writeEnd(ctx, buf3);
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