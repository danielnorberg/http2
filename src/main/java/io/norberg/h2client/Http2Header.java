package io.norberg.h2client;

import io.netty.util.AsciiString;

public final class Http2Header {

  private final AsciiString name;
  private final AsciiString value;
  private final boolean sensitive;

  private int hash;

  public Http2Header(final AsciiString name, final AsciiString value, final boolean sensitive) {
    this.name = name;
    this.value = value;
    this.sensitive = sensitive;
  }

  public AsciiString name() {
    return name;
  }

  public AsciiString value() {
    return value;
  }

  public boolean sensitive() {
    return sensitive;
  }

  int size() {
    return size(name, value);
  }

  static int size(final CharSequence name, final CharSequence value) {
    return name.length() + value.length() + 32;
  }

  static Http2Header of(final CharSequence name, final CharSequence value) {
    return of(name, value, false);
  }

  static Http2Header of(final CharSequence name, final CharSequence value, final boolean sensitive) {
    return new Http2Header(AsciiString.of(name), AsciiString.of(value), sensitive);
  }

  boolean equals(final AsciiString name, final AsciiString value) {
    if (!this.name.equals(name)) {
      return false;
    }
    return this.value.equals(value);
  }

  static int hashCode(final AsciiString name, final AsciiString value) {
    return (31 * name.hashCode()) ^ value.hashCode();
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

    return equals(that.name, that.value);
  }

  @Override
  public int hashCode() {
    if (hash == 0) {
      hash = hashCode(name, value);
    }
    return hash;
  }

  @Override
  public String toString() {
    return name + ": " + value + (sensitive ? "(sensitive)" : "");
  }
}
