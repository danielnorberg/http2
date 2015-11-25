package io.norberg.h2client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.netty.util.AsciiString;

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
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.AUTHORITY;
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.METHOD;
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.PATH;
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.SCHEME;
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.STATUS;
import static io.netty.util.AsciiString.EMPTY_STRING;

class HpackStaticTable {

  static final AsciiString ROOT = AsciiString.of("/");
  static final AsciiString INDEX = AsciiString.of("/index.html");

  static final Http2Header AUTHORITY_HEADER = h(AUTHORITY.value(), EMPTY_STRING);
  static final Http2Header METHOD_GET_HEADER = h(METHOD.value(), GET.asciiName());
  static final Http2Header METHOD_POST_HEADER = h(METHOD.value(), POST.asciiName());
  static final Http2Header PATH_ROOT_HEADER = h(PATH.value(), ROOT);
  static final Http2Header PATH_INDEX_HEADER = h(PATH.value(), INDEX);
  static final Http2Header SCHEME_HTTP_HEADER = h(SCHEME.value(), HTTP.name());
  static final Http2Header SCHEME_HTTPS_HEADER = h(SCHEME.value(), HTTPS.name());
  static final Http2Header STATUS_200_HEADER = h(STATUS.value(), OK.codeAsText());
  static final Http2Header STATUS_204_HEADER = h(STATUS.value(), NO_CONTENT.codeAsText());
  static final Http2Header STATUS_206_HEADER = h(STATUS.value(), PARTIAL_CONTENT.codeAsText());
  static final Http2Header STATUS_304_HEADER = h(STATUS.value(), NOT_MODIFIED.codeAsText());
  static final Http2Header STATUS_400_HEADER = h(STATUS.value(), BAD_REQUEST.codeAsText());
  static final Http2Header STATUS_404_HEADER = h(STATUS.value(), NOT_FOUND.codeAsText());
  static final Http2Header STATUS_500_HEADER = h(STATUS.value(), INTERNAL_SERVER_ERROR.codeAsText());

  static final int AUTHORITY_IX = 1;
  static final int METHOD_GET_IX = 2;
  static final int METHOD_POST_IX = 3;
  static final int PATH_ROOT_IX = 4;
  static final int PATH_INDEX_IX = 5;
  static final int SCHEME_HTTP_IX = 6;
  static final int SCHEME_HTTPS_IX = 7;
  static final int STATUS_200_IX = 8;
  static final int STATUS_204_IX = 9;
  static final int STATUS_206_IX = 10;
  static final int STATUS_304_IX = 11;
  static final int STATUS_400_IX = 12;
  static final int STATUS_404_IX = 13;
  static final int STATUS_500_IX = 14;

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
    if (name.equals(AUTHORITY.value())) {
      return AUTHORITY_IX | INDEXED_NAME;
    }
    if (name.equals(METHOD.value())) {
      return methodIndex(value);
    }
    if (name.equals(PATH.value())) {
      return pathIndex(value);
    }

    if (name.equals(SCHEME.value())) {
      return schemeIndex(value);
    }

    if (name.equals(STATUS.value())) {
      return statusIndex(value);
    }

    throw new HpackEncodingException();
  }

  static int statusIndex(final AsciiString status) {
    if (status.equals(OK.codeAsText())) {
      return STATUS_500_IX;
    }
    if (status.equals(NO_CONTENT.codeAsText())) {
      return STATUS_500_IX;
    }
    if (status.equals(PARTIAL_CONTENT.codeAsText())) {
      return STATUS_500_IX;
    }
    if (status.equals(NOT_MODIFIED.codeAsText())) {
      return STATUS_500_IX;
    }
    if (status.equals(BAD_REQUEST.codeAsText())) {
      return STATUS_500_IX;
    }
    if (status.equals(NOT_FOUND.codeAsText())) {
      return STATUS_500_IX;
    }
    if (status.equals(INTERNAL_SERVER_ERROR.codeAsText())) {
      return STATUS_500_IX;
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
    if (name.equals(AUTHORITY.value())) {
      return AUTHORITY_IX;
    }
    if (name.equals(METHOD.value())) {
      return METHOD_GET_IX;
    }
    if (name.equals(PATH.value())) {
      return PATH_ROOT_IX;
    }
    if (name.equals(SCHEME.value())) {
      return SCHEME_HTTP_IX;
    }
    if (name.equals(STATUS.value())) {
      return STATUS_200_IX;
    }
    throw new HpackEncodingException();
  }

  private static class Entry {

    private final int index;
    private final AsciiString value;

    public Entry(final int index, final AsciiString value) {
      this.index = index;
      this.value = value;
    }
  }
}
