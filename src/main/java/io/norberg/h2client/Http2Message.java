package io.norberg.h2client;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.BiConsumer;

import io.netty.util.AsciiString;

abstract class Http2Message {

  private AsciiString[] headers;
  private int headerIx;

  public boolean hasHeaders() {
    return headers != null;
  }

  public int headers() {
    return headerIx >> 1;
  }

  public AsciiString headerName(int i) {
    final int ix = i << 1;
    return headers[ix];
  }

  public AsciiString headerValue(int i) {
    final int ix = i << 1;
    return headers[ix + 1];
  }

  public void header(final AsciiString name, final AsciiString value) {
    if (headers == null) {
      headers = new AsciiString[16];
    }
    if (headerIx >= headers.length) {
      headers = Arrays.copyOf(headers, headers.length * 2);
    }
    headers[headerIx] = name;
    headers[headerIx + 1] = value;
    headerIx += 2;
  }

  String headersToString() {
    final int n = headers();
    if (n == 0) {
      return "{}";
    }
    final StringBuilder s = new StringBuilder().append('{');
    int i = 0;
    while (true) {
      s.append(headerName(i)).append('=').append(headerValue(i));
      i++;
      if (i >= n) {
        s.append('}');
        return s.toString();
      }
      s.append(',').append(' ');
    }
  }

  protected final void releaseHeaders() {
    if (headers != null) {
      Arrays.fill(headers, null);
      headers = null;
    }
  }

  public void forEachHeader(BiConsumer<AsciiString, AsciiString> action) {
    Objects.requireNonNull(action);
    for (int i = 0; i < headers(); i++) {
      action.accept(headerName(i), headerValue(i));
    }
  }
}
