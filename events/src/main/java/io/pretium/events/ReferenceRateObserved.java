package io.pretium.events;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One reference exchange rate as read from one source: {@code 1 base = rate quote} on {@code
 * rateDate}.
 *
 * <p>The ECB publishes the same rates through several channels. Each channel is its own source, so
 * the same rate arrives more than once and the catalog can check the channels against each other.
 *
 * @param runId the collection run that produced it
 * @param sourceId which source (channel) it came from
 * @param base the currency being priced, e.g. EUR
 * @param quote the currency it is priced in, e.g. USD
 * @param rateDate the business day the rate is for
 * @param rate units of quote per one unit of base
 * @param evidenceSha256 hash of the raw file it was read from
 */
public record ReferenceRateObserved(
    String runId,
    String sourceId,
    String base,
    String quote,
    LocalDate rateDate,
    BigDecimal rate,
    String evidenceSha256) {

  /** The Kafka key: the same rate from the same channel always lands on the same partition. */
  public String key() {
    return sourceId + "|" + base + "|" + quote + "|" + rateDate;
  }
}
