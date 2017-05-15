package io.norberg.http2;

import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpMethod.POST;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_MODIFIED;
import static io.netty.handler.codec.http.HttpResponseStatus.NO_CONTENT;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpResponseStatus.PARTIAL_CONTENT;
import static io.netty.handler.codec.http.HttpScheme.HTTP;
import static io.netty.handler.codec.http.HttpScheme.HTTPS;
import static io.netty.util.AsciiString.EMPTY_STRING;
import static io.norberg.http2.PseudoHeaders.AUTHORITY;
import static io.norberg.http2.PseudoHeaders.METHOD;
import static io.norberg.http2.PseudoHeaders.PATH;
import static io.norberg.http2.PseudoHeaders.SCHEME;
import static io.norberg.http2.PseudoHeaders.STATUS;

import io.netty.util.AsciiString;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class HpackStaticTable {

  private static final AsciiString ROOT = AsciiString.of("/");
  private static final AsciiString INDEX = AsciiString.of("/index.html");

  private static final Http2Header AUTHORITY_HEADER = h(AUTHORITY, EMPTY_STRING);
  private static final Http2Header METHOD_GET_HEADER = h(METHOD, GET.asciiName());
  private static final Http2Header METHOD_POST_HEADER = h(METHOD, POST.asciiName());
  private static final Http2Header PATH_ROOT_HEADER = h(PATH, ROOT);
  private static final Http2Header PATH_INDEX_HEADER = h(PATH, INDEX);
  private static final Http2Header SCHEME_HTTP_HEADER = h(SCHEME, HTTP.name());
  private static final Http2Header SCHEME_HTTPS_HEADER = h(SCHEME, HTTPS.name());
  private static final Http2Header STATUS_200_HEADER = h(STATUS, OK.codeAsText());
  private static final Http2Header STATUS_204_HEADER = h(STATUS, NO_CONTENT.codeAsText());
  private static final Http2Header STATUS_206_HEADER = h(STATUS, PARTIAL_CONTENT.codeAsText());
  private static final Http2Header STATUS_304_HEADER = h(STATUS, NOT_MODIFIED.codeAsText());
  private static final Http2Header STATUS_400_HEADER = h(STATUS, BAD_REQUEST.codeAsText());
  private static final Http2Header STATUS_404_HEADER = h(STATUS, NOT_FOUND.codeAsText());
  private static final Http2Header STATUS_500_HEADER = h(STATUS, INTERNAL_SERVER_ERROR.codeAsText());

  private static final int AUTHORITY_IX = 1;
  private static final int METHOD_GET_IX = 2;
  private static final int METHOD_POST_IX = 3;
  private static final int PATH_ROOT_IX = 4;
  private static final int PATH_INDEX_IX = 5;
  private static final int SCHEME_HTTP_IX = 6;
  private static final int SCHEME_HTTPS_IX = 7;
  private static final int STATUS_200_IX = 8;
  private static final int STATUS_204_IX = 9;
  private static final int STATUS_206_IX = 10;
  private static final int STATUS_304_IX = 11;
  private static final int STATUS_400_IX = 12;
  private static final int STATUS_404_IX = 13;
  private static final int STATUS_500_IX = 14;

  static final int INDEXED_NAME = 0x80000000;

  private static final int TABLE_LENGTH = 61;

  private static final Http2Header[] TABLE = {
      AUTHORITY_HEADER,
      METHOD_GET_HEADER,
      METHOD_POST_HEADER,
      PATH_ROOT_HEADER,
      PATH_INDEX_HEADER,
      SCHEME_HTTP_HEADER,
      SCHEME_HTTPS_HEADER,
      STATUS_200_HEADER,
      STATUS_204_HEADER,
      STATUS_206_HEADER,
      STATUS_304_HEADER,
      STATUS_400_HEADER,
      STATUS_404_HEADER,
      STATUS_500_HEADER,
      h("accept-charset", ""),
      h("accept-encoding", "gzip, deflate"),
      h("accept-language", ""),
      h("accept-ranges", ""),
      h("accept", ""),
      h("access-control-allow-origin", ""),
      h("age", ""),
      h("allow", ""),
      h("authorization", ""),
      h("cache-control", ""),
      h("content-disposition", ""),
      h("content-encoding", ""),
      h("content-language", ""),
      h("content-length", ""),
      h("content-location", ""),
      h("content-range", ""),
      h("content-type", ""),
      h("cookie", ""),
      h("date", ""),
      h("etag", ""),
      h("expect", ""),
      h("expires", ""),
      h("from", ""),
      h("host", ""),
      h("if-match", ""),
      h("if-modified-since", ""),
      h("if-none-match", ""),
      h("if-range", ""),
      h("if-unmodified-since", ""),
      h("last-modified", ""),
      h("link", ""),
      h("location", ""),
      h("max-forwards", ""),
      h("proxy-authenticate", ""),
      h("proxy-authorization", ""),
      h("range", ""),
      h("referer", ""),
      h("refresh", ""),
      h("retry-after", ""),
      h("server", ""),
      h("set-cookie", ""),
      h("strict-transport-security", ""),
      h("transfer-encoding", ""),
      h("user-agent", ""),
      h("vary", ""),
      h("via", ""),
      h("www-authenticate", "")
  };

  static {
    assert TABLE.length == TABLE_LENGTH;
  }

  private static final Map<AsciiString, List<Entry>> index = index(TABLE);

  private static Map<AsciiString, List<Entry>> index(Http2Header[] table) {
    // TODO: cache friendly index
    final Map<AsciiString, List<Entry>> index = new HashMap<>();
    for (int i = 0; i < table.length; i++) {
      final Http2Header header = table[i];
      index.computeIfAbsent(header.name(), name -> new ArrayList<>())
          .add(new Entry(i + 1, header.value()));
    }
    return index;
  }

  private static Http2Header h(final CharSequence name, final CharSequence value) {
    return Http2Header.of(name, value);
  }

  static Http2Header header(final int index) {
    return TABLE[index - 1];
  }

  static int length() {
    return TABLE_LENGTH;
  }

  static int headerIndex(final AsciiString name, final AsciiString value) throws HpackEncodingException {
    if (name.byteAt(0) == ':') {
      return pseudoHeaderIndex(name, value);
    }
    final List<Entry> entries = index.get(name);
    if (entries == null) {
      return 0;
    }
    for (final Entry entry : entries) {
      if (entry.value.equals(value)) {
        return entry.index;
      }
    }
    return entries.get(0).index | INDEXED_NAME;
  }

  static int pseudoHeaderIndex(final AsciiString name, final AsciiString value) throws HpackEncodingException {
    switch (name.byteAt(1)) {
      case 'a':
        if (name.equals(AUTHORITY)) {
          return AUTHORITY_IX | INDEXED_NAME;
        }
        break;
      case 'm':
        if (name.equals(METHOD)) {
          return methodIndex(value);
        }
        break;
      case 'p':
        if (name.equals(PATH)) {
          return pathIndex(value);
        }
        break;
      case 's':
        if (name.equals(SCHEME)) {
          return schemeIndex(value);
        }
        if (name.equals(STATUS)) {
          return statusIndex(value);
        }
        break;
    }
    throw new HpackEncodingException();
  }

  static int statusIndex(final AsciiString status) {
    if (status.equals(OK.codeAsText())) {
      return STATUS_200_IX;
    }
    switch (status.byteAt(0)) {
      case 'N':
        if (status.equals(NOT_FOUND.codeAsText())) {
          return STATUS_404_IX;
        }
        if (status.equals(NOT_MODIFIED.codeAsText())) {
          return STATUS_304_IX;
        }
        if (status.equals(NO_CONTENT.codeAsText())) {
          return STATUS_204_IX;
        }
        break;
      case 'P':
        if (status.equals(PARTIAL_CONTENT.codeAsText())) {
          return STATUS_206_IX;
        }
        break;
      case 'B':
        if (status.equals(BAD_REQUEST.codeAsText())) {
          return STATUS_400_IX;
        }
        break;
      case 'I':
        if (status.equals(INTERNAL_SERVER_ERROR.codeAsText())) {
          return STATUS_500_IX;
        }
        break;
    }
    return STATUS_200_IX | INDEXED_NAME;
  }

  static int schemeIndex(final AsciiString scheme) {
    if (scheme.equals(HTTPS.name())) {
      return SCHEME_HTTPS_IX;
    }
    if (scheme.equals(HTTP.name())) {
      return SCHEME_HTTP_IX;
    }
    return SCHEME_HTTP_IX | INDEXED_NAME;
  }

  static int pathIndex(final AsciiString path) {
    if (path.equals(ROOT)) {
      return PATH_ROOT_IX;
    }
    if (path.equals(INDEX)) {
      return PATH_INDEX_IX;
    }
    return PATH_ROOT_IX | INDEXED_NAME;
  }

  static int methodIndex(final AsciiString method) {
    if (method.equals(GET.asciiName())) {
      return METHOD_GET_IX;
    }
    if (method.equals(POST.asciiName())) {
      return METHOD_POST_IX;
    }
    return METHOD_GET_IX | INDEXED_NAME;
  }

  static int authorityIndex() {
    return AUTHORITY_IX | INDEXED_NAME;
  }

  static boolean isIndexedName(final int index) {
    return (index & INDEXED_NAME) != 0;
  }

  static int nameIndex(final int index) {
    return index & (~INDEXED_NAME);
  }

  static boolean isIndexedField(final int index) {
    return index != 0 && !isIndexedName(index);
  }

  static int nameIndex(final AsciiString name) throws HpackEncodingException {
    if (name.charAt(0) == ':') {
      return pseudoNameIndex(name);
    }
    final List<Entry> entries = index.get(name);
    if (entries == null) {
      return 0;
    }
    return entries.get(0).index;
  }

  static int pseudoNameIndex(final AsciiString name) throws HpackEncodingException {
    switch (name.byteAt(1)) {
      case 'A':
        if (name.equals(AUTHORITY)) {
          return AUTHORITY_IX;
        }
        break;
      case 'M':
        if (name.equals(METHOD)) {
          return METHOD_GET_IX;
        }
        break;
      case 'P':
        if (name.equals(PATH)) {
          return PATH_ROOT_IX;
        }
        break;
      case 'S':
        if (name.equals(SCHEME)) {
          return SCHEME_HTTP_IX;
        }
        if (name.equals(STATUS)) {
          return STATUS_200_IX;
        }
        break;
    }
    throw new HpackEncodingException();
  }

  static boolean isStaticIndex(final int index) {
    return index <= TABLE_LENGTH;
  }

  private static class Entry {

    private final int index;
    private final AsciiString value;

    Entry(final int index, final AsciiString value) {
      this.index = index;
      this.value = value;
    }
  }
}
