package io.pretium.collector.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pretium.collector.source.SourceCatalog;
import io.pretium.collector.source.SourceSpec;
import io.pretium.events.ReferenceRateObserved;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Bank of Canada's daily rates, read from its web page (captured 9 November 2026) and from its
 * Valet API (the CSV for 3 to 9 November). The page names currencies in words and shows five days
 * side by side; the CSV names each pair and has one line per day. The newest day must come out of
 * both identically: that is what makes a repaired recipe for the page checkable.
 */
class BocExtractorsTest {

  static final LocalDate DAY = LocalDate.of(2026, 11, 9);

  static SourceSpec.Recipe pageRecipe() throws Exception {
    return new SourceCatalog().find("boc-daily-html").orElseThrow().recipe();
  }

  static List<ReferenceRateObserved> read(Extractor extractor, SourceSpec spec, Path file)
      throws ExtractionException {
    EcbExtractorsTest.Collecting sink = new EcbExtractorsTest.Collecting();
    extractor.extract(spec, file, EcbExtractorsTest.CTX, sink);
    return sink.rates;
  }

  static Map<String, BigDecimal> byBase(List<ReferenceRateObserved> rates, LocalDate day) {
    return rates.stream()
        .filter(r -> r.rateDate().equals(day))
        .collect(Collectors.toMap(ReferenceRateObserved::base, ReferenceRateObserved::rate));
  }

  @Test
  void theValetCsvGivesCanadianDollarsForOneUnitAndSkipsBlanks() throws Exception {
    List<ReferenceRateObserved> rates =
        read(
            new BocValetCsvExtractor(),
            EcbExtractorsTest.spec("boc-valet-fx", SourceSpec.Format.BOC_VALET_CSV, null),
            EcbExtractorsTest.fixture("boc-valet-2026-11-03-to-09.csv"));

    assertThat(rates).allSatisfy(r -> assertThat(r.quote()).isEqualTo("CAD"));
    Map<String, BigDecimal> latest = byBase(rates, DAY);
    // 27 series; the ruble, riyal and dong were not published that day.
    assertThat(latest).hasSize(24).doesNotContainKeys("RUB", "SAR", "VND");
    assertThat(latest.get("AUD")).isEqualTo(new BigDecimal("0.9758"));
    assertThat(latest.get("EUR")).isEqualTo(new BigDecimal("1.5823"));
    assertThat(latest.get("USD")).isEqualTo(new BigDecimal("1.4123"));
    assertThat(latest.get("JPY")).isEqualTo(new BigDecimal("0.008940"));
    // The 5 days in the file are 3, 4, 5, 6 and 9 November: 24 rates each.
    assertThat(rates).hasSize(5 * 24);
  }

  @Test
  void thePageNamesCurrenciesInWordsAndTheNewestColumnIsRead() throws Exception {
    List<ReferenceRateObserved> page =
        read(
            new HtmlRateTableExtractor(),
            EcbExtractorsTest.spec(
                "boc-daily-html", SourceSpec.Format.HTML_RATE_TABLE, pageRecipe()),
            EcbExtractorsTest.fixture("boc-daily-2026-11-09.html"));

    // The date header is written 2026&#8209;11&#8209;09, with non-breaking hyphens.
    assertThat(page).hasSize(24).allSatisfy(r -> assertThat(r.rateDate()).isEqualTo(DAY));
    assertThat(page).allSatisfy(r -> assertThat(r.quote()).isEqualTo("CAD"));
    Map<String, BigDecimal> rate = byBase(page, DAY);
    assertThat(rate.get("GBP")).isEqualTo(new BigDecimal("1.8954"));
    assertThat(rate.get("EUR")).isEqualTo(new BigDecimal("1.5823"));
  }

  @Test
  void thePageAndTheApiAgreeOnEveryRate() throws Exception {
    Map<String, BigDecimal> api =
        byBase(
            read(
                new BocValetCsvExtractor(),
                EcbExtractorsTest.spec("boc-valet-fx", SourceSpec.Format.BOC_VALET_CSV, null),
                EcbExtractorsTest.fixture("boc-valet-2026-11-03-to-09.csv")),
            DAY);
    Map<String, BigDecimal> page =
        byBase(
            read(
                new HtmlRateTableExtractor(),
                EcbExtractorsTest.spec(
                    "boc-daily-html", SourceSpec.Format.HTML_RATE_TABLE, pageRecipe()),
                EcbExtractorsTest.fixture("boc-daily-2026-11-09.html")),
            DAY);

    assertThat(page).isEqualTo(api);
  }

  @Test
  void aCurrencyNameNotInTheTableStopsTheExtractionAndIsNamed(@TempDir Path tmp) throws Exception {
    // The real page with one currency renamed, as if the bank had reworded a label.
    Path page = tmp.resolve("renamed.html");
    String html =
        Files.readString(
            EcbExtractorsTest.fixture("boc-daily-2026-11-09.html"), StandardCharsets.UTF_8);
    assertThat(html).contains("Thai baht");
    Files.writeString(page, html.replace("Thai baht", "Thai baht (THB)"), StandardCharsets.UTF_8);

    assertThatThrownBy(
            () ->
                read(
                    new HtmlRateTableExtractor(),
                    EcbExtractorsTest.spec(
                        "boc-daily-html", SourceSpec.Format.HTML_RATE_TABLE, pageRecipe()),
                    page))
        .isInstanceOf(ExtractionException.class)
        .hasMessageContaining("Thai baht (THB)")
        .hasMessageContaining("name table");
  }

  @Test
  void aSeriesThatIsNotAPairAgainstCanadianDollarsIsRefused(@TempDir Path tmp) throws Exception {
    Path csv = tmp.resolve("odd.csv");
    Files.writeString(csv, "\"OBSERVATIONS\"\n\"date\",\"FXAUDUSD\"\n\"2026-11-09\",\"0.6\"\n");

    assertThatThrownBy(
            () ->
                read(
                    new BocValetCsvExtractor(),
                    EcbExtractorsTest.spec("boc-valet-fx", SourceSpec.Format.BOC_VALET_CSV, null),
                    csv))
        .isInstanceOf(ExtractionException.class)
        .hasMessageContaining("FXAUDUSD");
  }
}
