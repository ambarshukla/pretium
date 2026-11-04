package io.pretium.events;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One provider's price for one money-transfer service in one corridor, as observed by a source.
 *
 * <p>Field names follow money-transfer vocabulary, not the source's column names, so a second
 * source of quotes can be mapped onto the same message.
 *
 * @param runId the collection run that produced it
 * @param sourceId which source it came from
 * @param sourceRecordId the source's own id for the record, so a correction can be matched
 * @param period the source's collection period, e.g. {@code 2025_3Q}
 * @param sendCountry ISO 3166-1 alpha-3 code of the sending country
 * @param sendCountryName its name as the source gives it
 * @param receiveCountry ISO 3166-1 alpha-3 code of the receiving country
 * @param receiveCountryName its name as the source gives it
 * @param provider the provider's name as the source gives it
 * @param providerType bank, money transfer operator, post office, ...
 * @param paymentInstrument how the sender pays (cash, card, bank account, ...)
 * @param accessPoint where the sender starts (agent, online, app, ...)
 * @param speed how long until the money is available, as described by the source
 * @param transparent whether the provider disclosed the exchange rate it applied
 * @param interbankRate the reference ("mid-market") rate the source compared against
 * @param observedOn the day the price was observed
 * @param tiers the price at each surveyed amount
 * @param evidenceSha256 hash of the raw file it was read from
 */
public record ProviderQuoteObserved(
    String runId,
    String sourceId,
    String sourceRecordId,
    String period,
    String sendCountry,
    String sendCountryName,
    String receiveCountry,
    String receiveCountryName,
    String provider,
    String providerType,
    String paymentInstrument,
    String accessPoint,
    String speed,
    boolean transparent,
    BigDecimal interbankRate,
    LocalDate observedOn,
    List<Tier> tiers,
    String evidenceSha256) {

  public String key() {
    return sourceId + "|" + sourceRecordId;
  }

  /**
   * The price of sending one amount.
   *
   * @param amountUsd the surveyed amount expressed in US dollars (the source surveys 200 and 500)
   * @param sendAmount the same amount in the sending currency
   * @param sendCurrency ISO 4217 code of the sending currency
   * @param fee the fixed fee, in the sending currency
   * @param fxRate the exchange rate the provider applied (receive units per send unit)
   * @param fxMarginPct how far below the interbank rate that is, in percent
   * @param totalCostPct fee plus FX margin, as a percentage of the amount sent
   */
  public record Tier(
      int amountUsd,
      BigDecimal sendAmount,
      String sendCurrency,
      BigDecimal fee,
      BigDecimal fxRate,
      BigDecimal fxMarginPct,
      BigDecimal totalCostPct) {}
}
