package io.norberg.http2;

import static io.netty.handler.codec.http2.Http2CodecUtil.FRAME_HEADER_LENGTH;
import static io.netty.handler.codec.http2.Http2Flags.END_HEADERS;
import static io.netty.handler.codec.http2.Http2Flags.END_STREAM;
import static io.netty.handler.codec.http2.Http2FrameTypes.CONTINUATION;
import static io.netty.handler.codec.http2.Http2FrameTypes.HEADERS;
import static io.netty.util.ReferenceCountUtil.releaseLater;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.Test;

@SuppressWarnings("deprecation")
public class HeaderFramingTest {

  @Test
  public void frameHeaderBlock_TwoContinuationFrames() throws Exception {

    final ByteBuf buf = releaseLater(Unpooled.buffer(1024));

    buf.writeZero(FRAME_HEADER_LENGTH);
    byte[] block = "0123456789abcdefghijkl".getBytes(UTF_8);
    buf.writeBytes(block);

    final int streamId = 17;
    int newWriterIndex = HeaderFraming.frameHeaderBlock(buf, 0, block.length, 8, true, streamId);
    buf.writerIndex(newWriterIndex);

    int f0Length = buf.readUnsignedMedium();
    byte f0Type = buf.readByte();
    short f0Flags = buf.readByte();
    int f0StreamId = buf.readInt() & 0x7FFFFFFF;

    assertThat(f0Length, is(8));
    assertThat(f0Type, is(HEADERS));
    assertThat(f0Flags, is(END_STREAM));
    assertThat(f0StreamId, is(streamId));
    assertThat(releaseLater(buf.readBytes(f0Length)).toString(UTF_8), is("01234567"));

    int f1Length = buf.readUnsignedMedium();
    byte f1Type = buf.readByte();
    short f1Flags = buf.readByte();
    int f1StreamId = buf.readInt() & 0x7FFFFFFF;

    assertThat(f1Length, is(8));
    assertThat(f1Type, is(CONTINUATION));
    assertThat(f1Flags, is((short) 0));
    assertThat(f1StreamId, is(streamId));
    assertThat(releaseLater(buf.readBytes(f1Length)).toString(UTF_8), is("89abcdef"));

    int f2Length = buf.readUnsignedMedium();
    byte f2Type = buf.readByte();
    short f2Flags = buf.readByte();
    int f2StreamId = buf.readInt() & 0x7FFFFFFF;

    assertThat(f2Length, is(6));
    assertThat(f2Type, is(CONTINUATION));
    assertThat(f2Flags, is(END_HEADERS));
    assertThat(f2StreamId, is(streamId));
    assertThat(releaseLater(buf.readBytes(f2Length)).toString(UTF_8), is("ghijkl"));

  }

  @Test
  public void frameHeaderBlock_ThreeContinuationFrames() throws Exception {

    final ByteBuf buf = releaseLater(Unpooled.buffer(1024));

    buf.writeZero(FRAME_HEADER_LENGTH);
    byte[] block = "0123456789abcdefghijkl".getBytes(UTF_8);
    buf.writeBytes(block);

    final int streamId = 17;
    int newWriterIndex = HeaderFraming.frameHeaderBlock(buf, 0, block.length, 7, true, streamId);
    buf.writerIndex(newWriterIndex);

    int f0Length = buf.readUnsignedMedium();
    byte f0Type = buf.readByte();
    short f0Flags = buf.readByte();
    int f0StreamId = buf.readInt() & 0x7FFFFFFF;

    assertThat(f0Length, is(7));
    assertThat(f0Type, is(HEADERS));
    assertThat(f0Flags, is(END_STREAM));
    assertThat(f0StreamId, is(streamId));
    assertThat(releaseLater(buf.readBytes(f0Length)).toString(UTF_8), is("0123456"));

    int f1Length = buf.readUnsignedMedium();
    byte f1Type = buf.readByte();
    short f1Flags = buf.readByte();
    int f1StreamId = buf.readInt() & 0x7FFFFFFF;

    assertThat(f1Length, is(7));
    assertThat(f1Type, is(CONTINUATION));
    assertThat(f1Flags, is((short) 0));
    assertThat(f1StreamId, is(streamId));
    assertThat(releaseLater(buf.readBytes(f1Length)).toString(UTF_8), is("789abcd"));

    int f2Length = buf.readUnsignedMedium();
    byte f2Type = buf.readByte();
    short f2Flags = buf.readByte();
    int f2StreamId = buf.readInt() & 0x7FFFFFFF;

    assertThat(f2Length, is(7));
    assertThat(f2Type, is(CONTINUATION));
    assertThat(f2Flags, is((short) 0));
    assertThat(f2StreamId, is(streamId));
    assertThat(releaseLater(buf.readBytes(f2Length)).toString(UTF_8), is("efghijk"));

    int f3Length = buf.readUnsignedMedium();
    byte f3Type = buf.readByte();
    short f3Flags = buf.readByte();
    int f3StreamId = buf.readInt() & 0x7FFFFFFF;

    assertThat(f3Length, is(1));
    assertThat(f3Type, is(CONTINUATION));
    assertThat(f3Flags, is(END_HEADERS));
    assertThat(f3StreamId, is(streamId));
    assertThat(releaseLater(buf.readBytes(f3Length)).toString(UTF_8), is("l"));

  }

  @Test
  public void frameHeaderBlock_OneContinuationFrame() throws Exception {

    final ByteBuf buf = releaseLater(Unpooled.buffer(1024));

    buf.writeZero(FRAME_HEADER_LENGTH);
    byte[] block = "0123456789abcde".getBytes(UTF_8);
    buf.writeBytes(block);

    final int streamId = 17;
    int newWriterIndex = HeaderFraming.frameHeaderBlock(buf, 0, block.length, 8, true, streamId);
    buf.writerIndex(newWriterIndex);

    int f0Length = buf.readUnsignedMedium();
    byte f0Type = buf.readByte();
    short f0Flags = buf.readByte();
    int f0StreamId = buf.readInt() & 0x7FFFFFFF;

    assertThat(f0Length, is(8));
    assertThat(f0Type, is(HEADERS));
    assertThat(f0Flags, is(END_STREAM));
    assertThat(f0StreamId, is(streamId));
    assertThat(releaseLater(buf.readBytes(f0Length)).toString(UTF_8), is("01234567"));

    int f1Length = buf.readUnsignedMedium();
    byte f1Type = buf.readByte();
    short f1Flags = buf.readByte();
    int f1StreamId = buf.readInt() & 0x7FFFFFFF;

    assertThat(f1Length, is(7));
    assertThat(f1Type, is(CONTINUATION));
    assertThat(f1Flags, is(END_HEADERS));
    assertThat(f1StreamId, is(streamId));
    assertThat(releaseLater(buf.readBytes(f1Length)).toString(UTF_8), is("89abcde"));
  }

  @Test
  public void frameHeaderBlock_NoContinuationFrames() throws Exception {

    final ByteBuf buf = releaseLater(Unpooled.buffer(1024));

    buf.writeZero(FRAME_HEADER_LENGTH);
    String blockString = "0123456789abcdefghijkl";
    byte[] block = blockString.getBytes(UTF_8);
    buf.writeBytes(block);

    final int streamId = 17;
    int newWriterIndex = HeaderFraming.frameHeaderBlock(buf, 0, block.length, 32, true, streamId);
    buf.writerIndex(newWriterIndex);

    int f0Length = buf.readUnsignedMedium();
    byte f0Type = buf.readByte();
    short f0Flags = buf.readByte();
    int f0StreamId = buf.readInt() & 0x7FFFFFFF;

    assertThat(f0Length, is(block.length));
    assertThat(f0Type, is(HEADERS));
    assertThat(f0Flags, is((short) (END_HEADERS | END_STREAM)));
    assertThat(f0StreamId, is(streamId));
    assertThat(releaseLater(buf.readBytes(f0Length)).toString(UTF_8), is(blockString));
  }
}