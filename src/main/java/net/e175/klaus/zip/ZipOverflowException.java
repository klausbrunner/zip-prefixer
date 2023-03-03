package net.e175.klaus.zip;

import java.util.zip.ZipException;

/**
 * A ZipException that marks cases when the current ZIP file format cannot accommodate the new
 * offsets.
 */
public final class ZipOverflowException extends ZipException {
  public ZipOverflowException() {}

  public ZipOverflowException(String s) {
    super(s);
  }
}
