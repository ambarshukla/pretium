package io.pretium.catalog.quality;

import io.pretium.catalog.quality.Finding.Severity;
import io.pretium.events.ProviderQuoteObserved;
import io.pretium.events.ProviderQuoteObserved.Tier;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;

/**
 * The checks every provider price passes before it can be published.
 *
 * <p>The strongest checks are <em>identities</em>: relationships the numbers must satisfy because
 * of what they mean. A provider's total cost is its fee plus its exchange-rate margin; its margin
 * is how far its rate sits below the interbank rate. A source that reports all three numbers is
 * checkable against itself, with no second source needed. A value that breaks an identity by a
 * little is flagged; one that breaks it by a lot is held back.
 */
public final class QuoteChecks {

  static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  /** Rounding slack: the source publishes percentages to two decimal places. */
  static final BigDecimal WARN_PP = new BigDecimal("0.10");

  /** Beyond this the number contradicts itself, not just its rounding. */
  static final BigDecimal ERROR_PP = new BigDecimal("1.00");

  private QuoteChecks() {}

  public static List<Finding> check(ProviderQuoteObserved quote, Tier tier) {
    List<Finding> findings = new ArrayList<>();

    if (tier.sendAmount() == null || tier.sendAmount().signum() <= 0) {
      findings.add(
          new Finding("amount-positive", Severity.ERROR, "send amount is missing or not positive"));
      return findings;
    }
    if (tier.totalCostPct() == null) {
      findings.add(new Finding("total-cost-present", Severity.ERROR, "no total cost reported"));
      return findings;
    }
    if (tier.fee() != null && tier.fee().signum() < 0) {
      findings.add(
          new Finding("fee-non-negative", Severity.ERROR, "fee is negative: " + tier.fee()));
    }
    // Above 100% the sender pays more in charges than they send. In the real data this is a fee
    // recorded in the wrong currency, not a price.
    if (tier.totalCostPct().compareTo(HUNDRED) > 0
        || tier.totalCostPct().compareTo(HUNDRED.negate()) < 0) {
      findings.add(
          new Finding(
              "total-cost-range",
              Severity.ERROR,
              "total cost " + tier.totalCostPct() + "% is outside -100% to 100%"));
    }
    // A rate better than interbank is real, not an error: a first-transfer promotion, or a
    // country where the street rate differs from the official one. Published, but flagged, because
    // a reader comparing it with an everyday price is comparing different things. (An earlier
    // version treated a negative cost as impossible and quarantined 3,282 genuine prices.)
    if (quote.transparent() && tier.fxMarginPct() != null && tier.fxMarginPct().signum() < 0) {
      boolean large = tier.fxMarginPct().compareTo(new BigDecimal("-5")) < 0;
      findings.add(
          new Finding(
              "better-than-interbank",
              Severity.WARN,
              "the applied rate is "
                  + tier.fxMarginPct().negate()
                  + "% better than interbank: "
                  + (large
                      ? "most likely a parallel-market rate"
                      : "most likely a promotional rate")));
    }

    // Identity 1: total cost % = fee as % of amount + FX margin %.
    if (tier.fee() != null && tier.fxMarginPct() != null) {
      BigDecimal feePct =
          tier.fee().multiply(HUNDRED).divide(tier.sendAmount(), MathContext.DECIMAL64);
      BigDecimal implied = feePct.add(tier.fxMarginPct());
      BigDecimal gap = implied.subtract(tier.totalCostPct()).abs();
      if (gap.compareTo(WARN_PP) > 0) {
        findings.add(
            new Finding(
                "cost-identity",
                gap.compareTo(ERROR_PP) > 0 ? Severity.ERROR : Severity.WARN,
                "total cost "
                    + tier.totalCostPct()
                    + "% but fee "
                    + pct(feePct)
                    + "% + margin "
                    + tier.fxMarginPct()
                    + "% = "
                    + pct(implied)
                    + "% (off by "
                    + pct(gap)
                    + " pp)"));
      }
    }

    // Identity 2: FX margin % = (1 - provider rate / interbank rate) x 100. Only meaningful when
    // the
    // provider disclosed its rate; otherwise the source records the margin as 0 by convention.
    if (quote.transparent()
        && tier.fxRate() != null
        && tier.fxMarginPct() != null
        && quote.interbankRate() != null
        && quote.interbankRate().signum() > 0) {
      // The source computes its margin from full-precision rates, then publishes the rates rounded.
      // For a rate like 0.0007 (won to dong) the rounding alone moves the implied margin by several
      // percentage points, so the tolerance is widened by how much the published digits can hide,
      // and the check is skipped when that is more than the check could ever detect.
      BigDecimal rounding = roundingPp(tier.fxRate()).add(roundingPp(quote.interbankRate()));
      if (rounding.compareTo(ERROR_PP) > 0) {
        findings.add(
            new Finding(
                "margin-unverifiable",
                Severity.INFO,
                "rates are published with too few digits ("
                    + tier.fxRate()
                    + ", "
                    + quote.interbankRate()
                    + ") to verify the margin; up to "
                    + pct(rounding)
                    + " pp could be rounding"));
      } else {
        BigDecimal implied =
            BigDecimal.ONE
                .subtract(tier.fxRate().divide(quote.interbankRate(), MathContext.DECIMAL64))
                .multiply(HUNDRED);
        BigDecimal gap = implied.subtract(tier.fxMarginPct()).abs();
        if (gap.compareTo(WARN_PP.add(rounding)) > 0) {
          findings.add(
              new Finding(
                  "margin-identity",
                  gap.compareTo(ERROR_PP.add(rounding)) > 0 ? Severity.ERROR : Severity.WARN,
                  "margin "
                      + tier.fxMarginPct()
                      + "% but rate "
                      + tier.fxRate()
                      + " vs interbank "
                      + quote.interbankRate()
                      + " implies "
                      + pct(implied)
                      + "% (off by "
                      + pct(gap)
                      + " pp)"));
        }
      }
    }

    if (!quote.transparent()) {
      findings.add(
          new Finding(
              "rate-not-disclosed",
              Severity.INFO,
              "the provider did not disclose the exchange rate it applies; the true cost may be higher than shown"));
    }
    if (quote.observedOn() == null) {
      findings.add(new Finding("observed-date", Severity.WARN, "no observation date"));
    }
    return findings;
  }

  /** The publication decision that follows from a set of findings. */
  public static String status(List<Finding> findings) {
    if (findings.stream().anyMatch(f -> f.severity() == Severity.ERROR)) {
      return "QUARANTINED";
    }
    if (findings.stream().anyMatch(f -> f.severity() == Severity.WARN)) {
      return "PUBLISHED_WITH_WARNINGS";
    }
    return "PUBLISHED";
  }

  /**
   * The most a value's rounding can move a ratio built from it, in percentage points: half its last
   * published digit, relative to the value. 0.0007 can be anything from 0.00065 to 0.00075, which
   * is 7.14 pp; 58.25 hides at most 0.0086 pp.
   */
  static BigDecimal roundingPp(BigDecimal value) {
    BigDecimal halfUlp = value.stripTrailingZeros().ulp().divide(BigDecimal.TWO);
    return halfUlp.divide(value.abs(), MathContext.DECIMAL64).multiply(HUNDRED);
  }

  private static String pct(BigDecimal value) {
    return value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
  }
}
