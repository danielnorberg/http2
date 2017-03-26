package io.norberg.http2;

import org.junit.rules.TestWatcher;
import org.junit.runner.Description;

class LogTest extends TestWatcher {

  protected void starting(Description description) {
    System.err.println(
        "Starting test: " + description.getClassName() + '#' + description.getMethodName());
  }
}
