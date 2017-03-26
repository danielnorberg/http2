package io.norberg.h2client;

public class ConnectionClosedException extends Exception {

  private static final long serialVersionUID = -6558734203478460965L;

  public ConnectionClosedException() {
  }

  public ConnectionClosedException(final Throwable cause) {
    super(cause);
  }
}
