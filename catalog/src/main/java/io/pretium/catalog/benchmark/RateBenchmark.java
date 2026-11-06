package io.pretium.catalog.benchmark;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Checks the World Bank survey's "interbank rate" against the ECB's reference rates.
 *
 * <p>Why it matters: the survey's FX margin (how much worse than the market a provider's rate is)
 * is computed against that interbank rate. If it is wrong, every margin on the site is wrong. The
 * ECB publishes rates for about thirty currencies independently, so for corridors between those
 * currencies the survey can be checked.
 *
 * <p>What the comparison found in the real data: most disagreements are not errors. A transfer from
 * Japan to Indonesia may pay out in US dollars, and then the survey's rate is yen to dollars, not
 * yen to rupiah. So each quote is classified by which currency its rate actually fits, and only
 * quotes that fit none of them are worth a person's attention.
 *
 * <p>ECB rates are quoted per euro: {@code eurX} means "units of X for one euro". The cross rate
 * from A to B is therefore {@code eurB / eurA}.
 */
public final class RateBenchmark {

  /**
   * How far the survey's rate may be from the ECB's and still count as the same rate. The ECB fixes
   * its rates once a day at 14:15 Frankfurt time; the survey records a rate at the moment of
   * collection, so on a volatile day the two differ by a percent or so without either being wrong.
   */
  public static final BigDecimal TOLERANCE = new BigDecimal("0.02");

  private static final MathContext MC = MathContext.DECIMAL64;

  private RateBenchmark() {}

  /** Which currency the survey's rate turns out to be into. */
  public enum Payout {
    /** The receiving country's own currency: the normal case. */
    LOCAL,
    /** A rate of exactly 1: the money arrives in the currency it was sent in. */
    SEND_CURRENCY,
    /** The rate from the sending currency to US dollars. */
    USD,
    /** The rate from the sending currency to euros. */
    EUR,
    /**
     * The local rate written the wrong way round (send units per receive unit). Found in the real
     * data: Thailand to China in Q4 2016, 5.163 (baht per yuan) for baht to yuan, which is 0.193;
     * also Thailand to India and Indonesia that quarter, and Russia to Estonia and Lithuania in Q1
     * 2017. Worth a person's attention: a margin computed from it means nothing.
     */
    INVERTED,
    /** None of the above: worth a person's attention. */
    UNEXPLAINED,
    /** The ECB has no rate for one of the currencies on that day, so nothing can be said. */
    NO_REFERENCE
  }

  /**
   * One quote's rate and the ECB rates for its day.
   *
   * @param sendCurrency ISO code of the currency sent
   * @param receiveCurrency ISO code of the receiving country's currency
   * @param surveyRate the survey's interbank rate (receive units per send unit)
   * @param eurSend ECB units of the send currency per euro (1 for the euro), or null if none
   * @param eurReceive ECB units of the receive currency per euro (1 for the euro), or null if none
   * @param eurUsd ECB US dollars per euro, or null if none
   */
  public record Quote(
      String sendCurrency,
      String receiveCurrency,
      BigDecimal surveyRate,
      BigDecimal eurSend,
      BigDecimal eurReceive,
      BigDecimal eurUsd) {}

  /** The ECB's rate from the send currency to the receive currency, or null if it has none. */
  public static BigDecimal crossRate(BigDecimal eurSend, BigDecimal eurReceive) {
    if (eurSend == null || eurReceive == null || eurSend.signum() == 0) {
      return null;
    }
    return eurReceive.divide(eurSend, MC);
  }

  /** How far {@code rate} is from {@code reference}, in percent: +1 means 1% higher. */
  public static BigDecimal deviationPct(BigDecimal rate, BigDecimal reference) {
    return rate.divide(reference, MC).subtract(BigDecimal.ONE).movePointRight(2);
  }

  public static Payout classify(Quote q) {
    BigDecimal local = crossRate(q.eurSend(), q.eurReceive());
    if (local == null) {
      return Payout.NO_REFERENCE;
    }
    if (close(q.surveyRate(), local)) {
      return Payout.LOCAL;
    }
    if (q.surveyRate().compareTo(BigDecimal.ONE) == 0) {
      return Payout.SEND_CURRENCY;
    }
    if (!"USD".equals(q.sendCurrency())
        && close(q.surveyRate(), crossRate(q.eurSend(), q.eurUsd()))) {
      return Payout.USD;
    }
    if (!"EUR".equals(q.sendCurrency())
        && close(q.surveyRate(), crossRate(q.eurSend(), BigDecimal.ONE))) {
      return Payout.EUR;
    }
    if (close(q.surveyRate(), BigDecimal.ONE.divide(local, MC))) {
      return Payout.INVERTED;
    }
    return Payout.UNEXPLAINED;
  }

  /**
   * Whether the survey's rate is the reference rate, allowing for timing ({@link #TOLERANCE}) and
   * for rounding: the survey sometimes writes very small rates to few decimals (Korea to China in
   * 2022 to 2024: "0.0008" for won to dollars, which is 0.00077 rounded), so a rate also matches if
   * it is the reference rounded to the decimals the survey wrote.
   */
  static boolean close(BigDecimal rate, BigDecimal reference) {
    if (reference == null || reference.signum() == 0) {
      return false;
    }
    if (rate.divide(reference, MC).subtract(BigDecimal.ONE).abs().compareTo(TOLERANCE) <= 0) {
      return true;
    }
    int decimals = Math.max(0, rate.stripTrailingZeros().scale());
    return reference.setScale(decimals, RoundingMode.HALF_UP).compareTo(rate) == 0;
  }
}
