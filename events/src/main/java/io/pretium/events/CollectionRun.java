package io.pretium.events;

import java.time.Instant;

/**
 * The record of one attempt to collect one source. Emitted for every attempt, including failures
 * and blocks: an operator needs to see the attempts that produced nothing as clearly as the ones
 * that worked.
 *
 * @param runId unique id of this attempt; every observation it produced carries the same id
 * @param sourceId the source's id from its spec file
 * @param url what was requested
 * @param startedAt when the request was made
 * @param finishedAt when extraction finished (or the attempt gave up)
 * @param status the outcome
 * @param httpStatus the HTTP status code, or 0 if no response was received
 * @param bytes size of the response body
 * @param sha256 hash of the response body; the key of the raw evidence file
 * @param recipeVersion version of the extraction recipe that was applied
 * @param extracted number of observations produced
 * @param message a human-readable reason for anything other than success, otherwise null
 */
public record CollectionRun(
    String runId,
    String sourceId,
    String url,
    Instant startedAt,
    Instant finishedAt,
    Status status,
    int httpStatus,
    long bytes,
    String sha256,
    int recipeVersion,
    int extracted,
    String message) {

  /** How an attempt ended. */
  public enum Status {
    /** Fetched, new content, observations extracted. */
    SUCCEEDED,
    /** Fetched, but byte-for-byte identical to the last good fetch, so nothing was re-extracted. */
    UNCHANGED,
    /** Fetched, but the recipe could not extract anything: the source has probably changed. */
    EXTRACTION_FAILED,
    /** The source refused us (robots.txt, a 403, a bot challenge). Not retried, never bypassed. */
    BLOCKED,
    /** The request itself failed: timeout, DNS, a 5xx. Worth retrying later. */
    FETCH_FAILED
  }
}
