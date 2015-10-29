package io.norberg.h2client;

public class ConnectionClosedException extends Exception {

  public ConnectionClosedException() {
  }

  public ConnectionClosedException(final Throwable cause) {
    super(cause);
  }
}
