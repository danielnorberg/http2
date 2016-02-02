package io.norberg.h2client;

import org.junit.runner.JUnitCore;
import org.junit.runner.Request;

public class Http2ClientServerStress {

  public static void main(final String... args) {
    while (true) {
      JUnitCore junit = new JUnitCore();
      junit.run(Request.method(Http2ClientServerTest.class, "testReqRep"));
    }
  }
}
