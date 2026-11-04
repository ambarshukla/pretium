package io.pretium.collector.extract;

/**
 * The response did not have the shape the recipe expects: a selector matched nothing, a column is
 * missing, a date does not parse. This is the signal that a source has changed, so the message
 * should say exactly what was expected and not found.
 */
public class ExtractionException extends Exception {

  public ExtractionException(String message) {
    super(message);
  }

  public ExtractionException(String message, Throwable cause) {
    super(message, cause);
  }
}
