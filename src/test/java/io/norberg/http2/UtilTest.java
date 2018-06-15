package io.norberg.http2;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.*;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.Test;

public class UtilTest {

  @Test
  public void addData() {
    final ByteBuf data = ByteBufUtil.writeUtf8(UnpooledByteBufAllocator.DEFAULT, "foo");
    assertThat(Util.appendBytes(null, data), sameInstance(data));
    assertThat(Util.appendBytes(null, data), is(ByteBufUtil.writeUtf8(UnpooledByteBufAllocator.DEFAULT, "foo")));
    assertThat(Util.appendBytes(ByteBufUtil.writeUtf8(UnpooledByteBufAllocator.DEFAULT, "bar"), data),
        is(ByteBufUtil.writeUtf8(UnpooledByteBufAllocator.DEFAULT, "barfoo")));
  }
}