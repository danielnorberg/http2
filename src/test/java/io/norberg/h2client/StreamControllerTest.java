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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class StreamControllerTest {

  private StreamController<Stream> controller = new StreamController<>();

  @Mock StreamWriter writer;

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
    assertThat(stream.remoteWindow, is(controller.initialRemoteStreamWindow()));

    final ByteBuf buf = Unpooled.buffer(4096);

    // Prepare the mock writer
    final int estimatedInitialHeadersFrameSize = 17;
    final int estimatedDataFrameSize = size + 31;
    final int expectedBufferSize = estimatedInitialHeadersFrameSize + estimatedDataFrameSize;
    when(writer.estimateInitialHeadersFrameSize(stream)).thenReturn(estimatedInitialHeadersFrameSize);
    when(writer.estimateDataFrameSize(stream, size)).thenReturn(estimatedDataFrameSize);
    when(writer.writeStart(anyInt())).thenReturn(buf);

    // Write the stream
    controller.write(writer);

    // Verify the writer was correctly invoked
    verify(writer).estimateInitialHeadersFrameSize(stream);
    verify(writer).estimateDataFrameSize(stream, size);
    verify(writer).writeStart(expectedBufferSize);
    verify(writer).writeInitialHeadersFrame(buf, stream, false);
    verify(writer).writeDataFrame(buf, stream, size, true);
    verify(writer).writeEnd(buf);

    // Verify that the windows have been updated appropriately
    assertThat(controller.remoteConnectionWindow(), is(DEFAULT_INITIAL_WINDOW_SIZE - size));
    assertThat(stream.remoteWindow, is(DEFAULT_INITIAL_WINDOW_SIZE - size));
  }
}