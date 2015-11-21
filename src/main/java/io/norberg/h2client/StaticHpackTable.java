package io.norberg.h2client;

import io.netty.util.AsciiString;

class StaticHpackTable {

  private static final Http2Header[] TABLE = {
      h(":authority                 ", "              "), // 1
      h(":method                    ", "GET           "), // 2
      h(":method                    ", "POST          "), // 3
      h(":path                      ", "/             "), // 4
      h(":path                      ", "/index.html   "), // 5
      h(":scheme                    ", "http          "), // 6
      h(":scheme                    ", "https         "), // 7
      h(":status                    ", "200           "), // 8
      h(":status                    ", "204           "), // 9
      h(":status                    ", "206           "), // 10
      h(":status                    ", "304           "), // 11
      h(":status                    ", "400           "), // 12
      h(":status                    ", "404           "), // 13
      h(":status                    ", "500           "), // 14
      h("accept-charset             ", "              "), // 15
      h("accept-encoding            ", "gzip, deflate "), // 16
      h("accept-language            ", "              "), // 17
      h("accept-ranges              ", "              "), // 18
      h("accept                     ", "              "), // 19
      h("access-control-allow-origin", "              "), // 20
      h("age                        ", "              "), // 21
      h("allow                      ", "              "), // 22
      h("authorization              ", "              "), // 23
      h("cache-control              ", "              "), // 24
      h("content-disposition        ", "              "), // 25
      h("content-encoding           ", "              "), // 26
      h("content-language           ", "              "), // 27
      h("content-length             ", "              "), // 28
      h("content-location           ", "              "), // 29
      h("content-range              ", "              "), // 30
      h("content-type               ", "              "), // 31
      h("cookie                     ", "              "), // 32
      h("date                       ", "              "), // 33
      h("etag                       ", "              "), // 34
      h("expect                     ", "              "), // 35
      h("expires                    ", "              "), // 36
      h("from                       ", "              "), // 37
      h("host                       ", "              "), // 38
      h("if-match                   ", "              "), // 39
      h("if-modified-since          ", "              "), // 40
      h("if-none-match              ", "              "), // 41
      h("if-range                   ", "              "), // 42
      h("if-unmodified-since        ", "              "), // 43
      h("last-modified              ", "              "), // 44
      h("link                       ", "              "), // 45
      h("location                   ", "              "), // 46
      h("max-forwards               ", "              "), // 47
      h("proxy-authenticate         ", "              "), // 48
      h("proxy-authorization        ", "              "), // 49
      h("range                      ", "              "), // 50
      h("referer                    ", "              "), // 51
      h("refresh                    ", "              "), // 52
      h("retry-after                ", "              "), // 53
      h("server                     ", "              "), // 54
      h("set-cookie                 ", "              "), // 55
      h("strict-transport-security  ", "              "), // 56
      h("transfer-encoding          ", "              "), // 57
      h("user-agent                 ", "              "), // 58
      h("vary                       ", "              "), // 59
      h("via                        ", "              "), // 60
      h("www-authenticate           ", "              ")  // 61
  };

  private static Http2Header h(final String name, final String value) {
    return Http2Header.of(AsciiString.of(name.trim()), AsciiString.of(value.trim()));
  }

  static Http2Header header(final int index) {
    return TABLE[index - 1];
  }

  static int length() {
    return TABLE.length;
  }
}
