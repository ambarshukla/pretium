package io.pretium.catalog.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import io.pretium.catalog.benchmark.RateBenchmark.Payout;
import io.pretium.catalog.benchmark.RateBenchmark.Quote;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Real quotes from the World Bank survey, Q3 2025 (record ids in the comments), against the ECB's
 * real reference rates for the day each was observed.
 */
class RateBenchmarkTest {

  static BigDecimal d(String s) {
    return new BigDecimal(s);
  }

  /** Record 1212252176: MoneyGram, United Kingdom to India, 20 August 2025. */
  @Test
  void aRateIntoTheReceivingCountrysCurrencyMatchesTheEcbCrossRate() {
    // ECB, 20 August 2025: 101.4305 INR and 0.8645 GBP per euro.
    Quote q = new Quote("GBP", "INR", d("117.5667080000"), d("0.8645"), d("101.4305"), d("1.1651"));

    assertThat(RateBenchmark.classify(q)).isEqualTo(Payout.LOCAL);
    BigDecimal cross = RateBenchmark.crossRate(q.eurSend(), q.eurReceive());
    assertThat(cross).isEqualByComparingTo("117.3285135916715");
    assertThat(RateBenchmark.deviationPct(q.surveyRate(), cross))
        .isBetween(d("0.20"), d("0.21")); // the survey's rate is 0.2% above the ECB's
  }

  /**
   * Record 1212253479: Japan Post Bank, Japan to Indonesia, 27 August 2025. The survey's rate is
   * 0.00676: nothing like yen to rupiah (110.9), exactly yen to US dollars. The transfer pays out
   * in dollars.
   */
  @Test
  void aRateThatIsReallyIntoUsDollarsIsRecognised() {
    // ECB, 27 August 2025: 171.63 JPY, 19038.95 IDR and 1.1593 USD per euro.
    Quote q = new Quote("JPY", "IDR", d("0.0067601825"), d("171.63"), d("19038.95"), d("1.1593"));

    assertThat(RateBenchmark.classify(q)).isEqualTo(Payout.USD);
  }

  /**
   * Record 1212252861: MoneyGram, United Kingdom to Romania, 29 August 2025. 1.1546 is not pounds
   * to lei (5.85) but pounds to euros: the transfer pays out in euros.
   */
  @Test
  void aRateThatIsReallyIntoEurosIsRecognised() {
    // ECB, 29 August 2025: 0.8668 GBP, 5.0728 RON and 1.1658 USD per euro.
    Quote q = new Quote("GBP", "RON", d("1.1545792555"), d("0.8668"), d("5.0728"), d("1.1658"));

    assertThat(RateBenchmark.classify(q)).isEqualTo(Payout.EUR);
  }

  /** Record 1212250609: Erste Bank, Austria to Hungary, 21 August 2025, rate exactly 1. */
  @Test
  void aRateOfOneMeansTheMoneyArrivesInTheCurrencySent() {
    // ECB, 21 August 2025: 395.75 HUF and 1.1639 USD per euro.
    Quote q = new Quote("EUR", "HUF", d("1.0000000000"), BigDecimal.ONE, d("395.75"), d("1.1639"));

    assertThat(RateBenchmark.classify(q)).isEqualTo(Payout.SEND_CURRENCY);
  }

  @Test
  void withoutAnEcbRateNothingCanBeSaid() {
    Quote q = new Quote("GBP", "INR", d("117.5667080000"), d("0.8645"), null, d("1.1651"));

    assertThat(RateBenchmark.classify(q)).isEqualTo(Payout.NO_REFERENCE);
  }

  /**
   * Record 1220163252: Bangkok Bank, Thailand to China, 10 November 2016. The survey gives 5.163,
   * which is baht per yuan; the transfer goes the other way, baht to yuan, at 0.193.
   */
  @Test
  void aRateWrittenTheWrongWayRoundIsFlagged() {
    // ECB, 10 November 2016: 38.307 THB, 7.4065 CNY and 1.0895 USD per euro.
    Quote q = new Quote("THB", "CNY", d("5.1630900000"), d("38.307"), d("7.4065"), d("1.0895"));

    assertThat(RateBenchmark.classify(q)).isEqualTo(Payout.INVERTED);
  }

  /**
   * Korea to China, 19 August 2024: the survey writes "0.0007" for won to US dollars, which the ECB
   * puts at 0.000748. Off by 6.4% as a ratio, but it is the ECB rate rounded to the four decimals
   * the survey uses.
   */
  @Test
  void aRateRoundedToFewDecimalsStillMatches() {
    // ECB, 19 August 2024: 1476.07 KRW, 7.8848 CNY and 1.1041 USD per euro.
    Quote q = new Quote("KRW", "CNY", d("0.0007000000"), d("1476.07"), d("7.8848"), d("1.1041"));

    assertThat(RateBenchmark.classify(q)).isEqualTo(Payout.USD);
  }
}
