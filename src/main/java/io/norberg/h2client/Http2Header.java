package io.norberg.h2client;

import io.netty.util.AsciiString;
import io.netty.util.ByteString;

public class Http2Header {

  private final AsciiString name;
  private final ByteString value;
  private final boolean sensitive;

  public Http2Header(final AsciiString name, final ByteString value, final boolean sensitive) {
    this.name = name;
    this.value = value;
    this.sensitive = sensitive;
  }

  public AsciiString name() {
    return name;
  }

  public ByteString value() {
    return value;
  }

  public boolean sensitive() {
    return sensitive;
  }

  static Http2Header of(final AsciiString name, final ByteString value) {
    return of(name, value, false);
  }

  static Http2Header of(final AsciiString name, final ByteString value, final boolean sensitive) {
    return new Http2Header(name, value, sensitive);
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    final Http2Header that = (Http2Header) o;

    if (sensitive != that.sensitive) {
      return false;
    }
    if (name != null ? !name.equals(that.name) : that.name != null) {
      return false;
    }
    return !(value != null ? !value.equals(that.value) : that.value != null);

  }

  @Override
  public int hashCode() {
    int result = name != null ? name.hashCode() : 0;
    result = 31 * result + (value != null ? value.hashCode() : 0);
    result = 31 * result + (sensitive ? 1 : 0);
    return result;
  }

  @Override
  public String toString() {
    return name + ": " + value + (sensitive ? "(sensitive)" : "");
  }
}