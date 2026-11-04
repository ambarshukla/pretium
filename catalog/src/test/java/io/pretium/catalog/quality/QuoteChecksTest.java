package io.pretium.catalog.quality;

import static org.assertj.core.api.Assertions.assertThat;

import io.pretium.catalog.quality.Finding.Severity;
import io.pretium.events.ProviderQuoteObserved;
import io.pretium.events.ProviderQuoteObserved.Tier;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The checks against real World Bank survey rows (Q2 2016, ids 620160001 and 620160003). The
 * passing cases are the real numbers; each failing case changes exactly one real number and says
 * which.
 */
class QuoteChecksTest {

  static ProviderQuoteObserved quote(boolean transparent, String interbank, Tier tier) {
    return new ProviderQuoteObserved(
        "run",
        "wb-rpw",
        "620160001",
        "2016_2Q",
        "AGO",
        "Angola",
        "NAM",
        "Namibia",
        "MoneyGram",
        "Money Transfer Operator",
        "Cash",
        "Agent",
        "Less than one hour",
        transparent,
        new BigDecimal(interbank),
        LocalDate.of(2016, 5, 11),
        List.of(tier),
        "sha");
  }

  // MoneyGram, Angola to Namibia, USD 200: AOA 33000, fee 2900, rate 0.084, margin 7.49%, total
  // 16.28%.
  static Tier moneygram(String fee, String margin, String total) {
    return new Tier(
        200,
        new BigDecimal("33000"),
        "AOA",
        new BigDecimal(fee),
        new BigDecimal("0.084"),
        new BigDecimal(margin),
        new BigDecimal(total));
  }

  @Test
  void aRealTransparentQuotePassesBothIdentities() {
    ProviderQuoteObserved q = quote(true, "0.0908", moneygram("2900", "7.49", "16.28"));
    List<Finding> findings = QuoteChecks.check(q, q.tiers().get(0));

    // 2900 / 33000 = 8.79%, + 7.49% = 16.28%; and 1 - 0.084 / 0.0908 = 7.49%.
    assertThat(findings).isEmpty();
    assertThat(QuoteChecks.status(findings)).isEqualTo("PUBLISHED");
  }

  @Test
  void aRealOpaqueQuoteIsPublishedWithANote() {
    // Standard Bank, same corridor: fee 9075, rate not disclosed (recorded as 1, margin 0), total
    // 27.5%.
    Tier tier =
        new Tier(
            200,
            new BigDecimal("33000"),
            "AOA",
            new BigDecimal("9075"),
            BigDecimal.ONE,
            BigDecimal.ZERO,
            new BigDecimal("27.5"));
    ProviderQuoteObserved q = quote(false, "1", tier);
    List<Finding> findings = QuoteChecks.check(q, tier);

    assertThat(findings).extracting(Finding::rule).containsExactly("rate-not-disclosed");
    assertThat(QuoteChecks.status(findings)).isEqualTo("PUBLISHED");
  }

  @Test
  void aTotalOffByRoundingIsFlaggedButPublished() {
    ProviderQuoteObserved q =
        quote(true, "0.0908", moneygram("2900", "7.49", "16.50")); // total changed
    List<Finding> findings = QuoteChecks.check(q, q.tiers().get(0));

    assertThat(findings)
        .singleElement()
        .satisfies(
            f -> {
              assertThat(f.rule()).isEqualTo("cost-identity");
              assertThat(f.severity()).isEqualTo(Severity.WARN);
              assertThat(f.detail()).contains("16.50", "8.79", "16.28");
            });
    assertThat(QuoteChecks.status(findings)).isEqualTo("PUBLISHED_WITH_WARNINGS");
  }

  @Test
  void aFeeThatContradictsTheTotalIsQuarantined() {
    ProviderQuoteObserved q = quote(true, "0.0908", moneygram("29000", "7.49", "16.28")); // fee x10
    List<Finding> findings = QuoteChecks.check(q, q.tiers().get(0));

    assertThat(findings).extracting(Finding::rule).contains("cost-identity");
    assertThat(QuoteChecks.status(findings)).isEqualTo("QUARANTINED");
  }

  @Test
  void aRateBetterThanInterbankIsRealAndPublishedWithAWarning() {
    // Remitly, Canada to India, 2021_1Q, USD 500: no fee, rate 58.25 against interbank 57.7336.
    Tier tier =
        new Tier(
            500,
            new BigDecimal("500"),
            "CAD",
            BigDecimal.ZERO,
            new BigDecimal("58.25"),
            new BigDecimal("-0.89"),
            new BigDecimal("-0.89"));
    ProviderQuoteObserved q = quote(true, "57.7336", tier);
    List<Finding> findings = QuoteChecks.check(q, tier);

    assertThat(findings).extracting(Finding::rule).containsExactly("better-than-interbank");
    assertThat(findings.get(0).detail()).contains("promotional");
    assertThat(QuoteChecks.status(findings)).isEqualTo("PUBLISHED_WITH_WARNINGS");
  }

  @Test
  void ratesPublishedWithTooFewDigitsAreNotHeldAgainstTheProvider() {
    // MoneyGram, Korea to Vietnam, 2024_3Q, USD 500: both rates published as 0.0007, margin 0.94%.
    // 0.0007 can be anything from 0.00065 to 0.00075, so the margin cannot be checked from them.
    Tier tier =
        new Tier(
            500,
            new BigDecimal("690000"),
            "KRW",
            new BigDecimal("5000"),
            new BigDecimal("0.0007"),
            new BigDecimal("0.94"),
            new BigDecimal("1.66"));
    ProviderQuoteObserved q = quote(true, "0.0007", tier);
    List<Finding> findings = QuoteChecks.check(q, tier);

    assertThat(findings).extracting(Finding::rule).containsExactly("margin-unverifiable");
    assertThat(QuoteChecks.status(findings)).isEqualTo("PUBLISHED");
  }

  @Test
  void aTotalAboveOneHundredPercentIsQuarantined() {
    // Türkiye to Bulgaria, 2025_3Q, USD 200: fee 661.47 on TRY 570 sent, total 118.6%.
    Tier tier =
        new Tier(
            200,
            new BigDecimal("570"),
            "TRY",
            new BigDecimal("661.47"),
            new BigDecimal("0.0237"),
            new BigDecimal("2.55"),
            new BigDecimal("118.6"));
    ProviderQuoteObserved q = quote(true, "0.0243205448", tier);

    assertThat(QuoteChecks.status(QuoteChecks.check(q, tier))).isEqualTo("QUARANTINED");
  }

  @Test
  void roundingBoundsFollowThePublishedDigits() {
    assertThat(QuoteChecks.roundingPp(new BigDecimal("0.0007")))
        .isEqualByComparingTo("7.142857142857143");
    assertThat(QuoteChecks.roundingPp(new BigDecimal("58.25")).doubleValue()).isLessThan(0.01);
  }

  @Test
  void aMarginThatContradictsTheRatesIsQuarantined() {
    ProviderQuoteObserved q =
        quote(true, "0.1208", moneygram("2900", "7.49", "16.28")); // interbank changed
    List<Finding> findings = QuoteChecks.check(q, q.tiers().get(0));

    assertThat(findings).extracting(Finding::rule).containsExactly("margin-identity");
    assertThat(QuoteChecks.status(findings)).isEqualTo("QUARANTINED");
  }
}
