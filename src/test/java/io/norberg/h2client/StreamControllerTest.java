package io.norberg.h2client;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import static io.norberg.h2client.Http2Protocol.DEFAULT_INITIAL_WINDOW_SIZE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.verify;
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
  public void testConnectionWindow() throws Exception {
    assertThat(controller.remoteConnectionWindow(), is(DEFAULT_INITIAL_WINDOW_SIZE));
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
    controller.write(ctx, writer);

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
}