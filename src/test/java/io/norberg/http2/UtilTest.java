package io.norberg.http2;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.*;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.hamcrest.Matchers;
import org.junit.Test;

public class UtilTest {

  @Test
  public void addData() {
    final ByteBuf data = ByteBufUtil.writeUtf8(UnpooledByteBufAllocator.DEFAULT, "foo");
    assertThat(Util.addData(null, data), sameInstance(data));
    assertThat(Util.addData(null, data), is(ByteBufUtil.writeUtf8(UnpooledByteBufAllocator.DEFAULT, "foo")));
    assertThat(Util.addData(ByteBufUtil.writeUtf8(UnpooledByteBufAllocator.DEFAULT, "bar"), data),
        is(ByteBufUtil.writeUtf8(UnpooledByteBufAllocator.DEFAULT, "barfoo")));
  }
}