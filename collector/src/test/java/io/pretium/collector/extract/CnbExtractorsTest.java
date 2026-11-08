package io.pretium.collector.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pretium.collector.source.SourceSpec;
import io.pretium.events.ReferenceRateObserved;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The Czech National Bank's fixing, read from the page and the text file captured on 2026-11-07
 * (the fixing of 6 November; see docs/PROVENANCE.md). Like the three ECB channels, the two must
 * yield identical numbers: that is what makes a repaired recipe for the page checkable.
 */
public class CnbExtractorsTest {

  public static final SourceSpec.Recipe PAGE_RECIPE =
      new SourceSpec.Recipe(
          1,
          Map.of(
              "quote", "CZK",
              "dateSelector", "p:containsOwn(Validity for)",
              "dateRegex", "Validity for (\\d{1,2} \\w{3} \\d{4})",
              "dateFormat", "d MMM yyyy",
              "rowSelector", "table.currency-table tbody tr",
              "currencySelector", "td:nth-of-type(4)",
              "amountSelector", "td:nth-of-type(3)",
              "rateSelector", "td:nth-of-type(5)"));

  static List<ReferenceRateObserved> read(Extractor extractor, SourceSpec spec, Path file)
      throws ExtractionException {
    EcbExtractorsTest.Collecting sink = new EcbExtractorsTest.Collecting();
    extractor.extract(spec, file, EcbExtractorsTest.CTX, sink);
    return sink.rates;
  }

  static Map<String, BigDecimal> byBase(List<ReferenceRateObserved> rates) {
    return rates.stream()
        .collect(Collectors.toMap(ReferenceRateObserved::base, ReferenceRateObserved::rate));
  }

  @Test
  void theTextFileGivesKorunasForOneUnitOfEachCurrency() throws Exception {
    List<ReferenceRateObserved> rates =
        read(
            new CnbFixingTxtExtractor(),
            EcbExtractorsTest.spec("cnb-fixing-txt", SourceSpec.Format.CNB_FIXING_TXT, null),
            EcbExtractorsTest.fixture("cnb-fixing-2026-11-06.txt"));

    assertThat(rates).hasSize(30);
    assertThat(rates).allSatisfy(r -> assertThat(r.quote()).isEqualTo("CZK"));
    assertThat(rates)
        .allSatisfy(r -> assertThat(r.rateDate()).isEqualTo(LocalDate.of(2026, 11, 6)));
    Map<String, BigDecimal> rate = byBase(rates);
    assertThat(rate.get("USD")).isEqualByComparingTo("21.804");
    assertThat(rate.get("EUR")).isEqualByComparingTo("24.345");
    // "100 HUF = 6.693": per forint, exactly, no rounding
    assertThat(rate.get("HUF")).isEqualTo(new BigDecimal("0.06693"));
    // "1000 IDR = 1.206"
    assertThat(rate.get("IDR")).isEqualTo(new BigDecimal("0.001206"));
  }

  @Test
  void thePageAndTheTextFileAgreeOnEveryRate() throws Exception {
    Map<String, BigDecimal> file =
        byBase(
            read(
                new CnbFixingTxtExtractor(),
                EcbExtractorsTest.spec("cnb-fixing-txt", SourceSpec.Format.CNB_FIXING_TXT, null),
                EcbExtractorsTest.fixture("cnb-fixing-2026-11-06.txt")));
    List<ReferenceRateObserved> page =
        read(
            new HtmlRateTableExtractor(),
            EcbExtractorsTest.spec(
                "cnb-fixing-html", SourceSpec.Format.HTML_RATE_TABLE, PAGE_RECIPE),
            EcbExtractorsTest.fixture("cnb-fixing-2026-11-06.html"));

    assertThat(page).allSatisfy(r -> assertThat(r.rateDate()).isEqualTo(LocalDate.of(2026, 11, 6)));
    assertThat(byBase(page)).isEqualTo(file);
  }

  @Test
  void theYearlyFileRereadsItsColumnsAtEveryHeader() throws Exception {
    // Real lines from year.txt?year=2005: the first two, then the turn of March into April, when
    // the bank added the Chinese renminbi and the header changed from 31 currencies to 36.
    List<ReferenceRateObserved> rates =
        read(
            new CnbFixingTxtExtractor(),
            EcbExtractorsTest.spec("cnb-fixing-txt", SourceSpec.Format.CNB_FIXING_TXT, null),
            EcbExtractorsTest.fixture("cnb-fixing-year-2005-excerpt.txt"));

    Map<LocalDate, Map<String, BigDecimal>> byDay = new HashMap<>();
    rates.forEach(
        r -> byDay.computeIfAbsent(r.rateDate(), d -> new HashMap<>()).put(r.base(), r.rate()));
    assertThat(byDay.get(LocalDate.of(2005, 1, 3))).hasSize(31);
    assertThat(byDay.get(LocalDate.of(2005, 1, 3)).get("HUF")).isEqualTo(new BigDecimal("0.12362"));
    assertThat(byDay.get(LocalDate.of(2005, 1, 3)).get("USD")).isEqualByComparingTo("22.484");
    assertThat(byDay.get(LocalDate.of(2005, 3, 31))).doesNotContainKey("CNY");
    assertThat(byDay.get(LocalDate.of(2005, 4, 1))).hasSize(36);
    assertThat(byDay.get(LocalDate.of(2005, 4, 1)).get("CNY")).isEqualByComparingTo("2.800");
    assertThat(byDay.get(LocalDate.of(2005, 4, 1)).get("USD")).isEqualByComparingTo("23.173");
  }

  @Test
  void aRateQuotedForAnAmountThatIsNotAPowerOfTenIsRefused() {
    assertThatThrownBy(() -> Numbers.perUnit(new BigDecimal("6.693"), new BigDecimal("50")))
        .isInstanceOf(ExtractionException.class)
        .hasMessageContaining("not a power of ten");
  }

  @Test
  void aRecipeWithBothBaseAndQuoteIsRefused() throws Exception {
    Map<String, String> both = new HashMap<>(PAGE_RECIPE.settings());
    both.put("base", "EUR");
    HtmlRateTableExtractor.Trace trace =
        HtmlRateTableExtractor.trace(
            org.jsoup.Jsoup.parse(
                EcbExtractorsTest.fixture("cnb-fixing-2026-11-06.html").toFile(), "UTF-8"),
            new SourceSpec.Recipe(1, both));
    assertThat(trace.error()).contains("exactly one of 'base'");
  }
}
