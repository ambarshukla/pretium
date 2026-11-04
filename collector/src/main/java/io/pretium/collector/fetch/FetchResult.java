package io.pretium.collector.fetch;

import java.nio.file.Path;

/**
 * What came back from one request.
 *
 * @param outcome how the request ended
 * @param httpStatus the status code, or 0 if there was no response
 * @param body the downloaded body in a temporary file, or null if nothing was downloaded
 * @param etag the response's ETag, if any, for the next conditional request
 * @param message why, when the outcome is not {@link Outcome#OK}
 */
public record FetchResult(Outcome outcome, int httpStatus, Path body, String etag, String message) {

  public enum Outcome {
    /** A body was downloaded. */
    OK,
    /** The server said 304 Not Modified: our last copy is still current. */
    NOT_MODIFIED,
    /** robots.txt, a 401/403/451 or a bot challenge. We stop; we do not try to get round it. */
    BLOCKED,
    /** Timeout, connection failure, 429 or 5xx. */
    FAILED
  }
}
