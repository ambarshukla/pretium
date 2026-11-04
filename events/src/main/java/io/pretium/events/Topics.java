package io.pretium.events;

/**
 * Kafka topic names. One topic per message type, versioned in the name: a breaking change to a
 * message ships as a new topic that consumers move to, rather than as a surprise on an old one.
 */
public final class Topics {

  /** One message per attempt to collect a source, whatever the outcome. */
  public static final String COLLECTION_RUNS = "pretium.collection-runs.v1";

  /** One message per reference exchange rate read from a source. */
  public static final String REFERENCE_RATES = "pretium.reference-rates.v1";

  /** One message per provider price observation (one service, one corridor, one period). */
  public static final String PROVIDER_QUOTES = "pretium.provider-quotes.v1";

  private Topics() {}
}
