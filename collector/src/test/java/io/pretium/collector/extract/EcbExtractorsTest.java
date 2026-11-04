package io.pretium.collector.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pretium.collector.source.SourceSpec;
import io.pretium.events.ProviderQuoteObserved;
import io.pretium.events.ReferenceRateObserved;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The three ECB channels, read from real responses captured on 2026-11-03 (see docs/PROVENANCE.md).
 * All three must yield the same numbers; that agreement is what the catalog's reconciliation relies
 * on, and what makes a repaired HTML recipe checkable.
 */
class EcbExtractorsTest {

  static final Extractor.Context CTX = new Extractor.Context("run-1", "sha-1");

  static final SourceSpec.Recipe HTML_RECIPE =
      new SourceSpec.Recipe(
          1,
          Map.of(
              "base", "EUR",
              "dateSelector", "div.jumbo-box h3",
              "dateFormat", "d MMMM yyyy",
              "rowSelector", "table.forextable tbody tr",
              "currencySelector", "td.currency",
              "rateSelector", "span.rate"));

  static SourceSpec spec(String id, SourceSpec.Format format, SourceSpec.Recipe recipe) {
    return new SourceSpec(
        id,
        id,
        "ECB",
        "test",
        null,
        "https://www.ecb.europa.eu/",
        null,
        format,
        "0 0 0 * * *",
        "UTC",
        recipe);
  }

  static Path fixture(String name) throws URISyntaxException {
    return Path.of(EcbExtractorsTest.class.getResource("/fixtures/" + name).toURI());
  }

  static class Collecting implements ObservationSink {
    final List<ReferenceRateObserved> rates = new ArrayList<>();

    @Override
    public void rate(ReferenceRateObserved rate) {
      rates.add(rate);
    }

    @Override
    public void quote(ProviderQuoteObserved quote) {
      throw new AssertionError("ECB sources produce no quotes");
    }

    BigDecimal rateOf(String currency) {
      return rates.stream()
          .filter(r -> r.quote().equals(currency))
          .findFirst()
          .orElseThrow()
          .rate();
    }
  }

  @Test
  void xmlReadsEveryCurrencyForTheDay() throws Exception {
    Collecting sink = new Collecting();
    int n =
        new EcbEurofxrefXmlExtractor()
            .extract(
                spec("x", SourceSpec.Format.ECB_EUROFXREF_XML, null),
                fixture("ecb-eurofxref-2026-11-03.xml"),
                CTX,
                sink);

    assertThat(n).isEqualTo(sink.rates.size()).isGreaterThan(25);
    assertThat(sink.rates)
        .allSatisfy(
            r -> {
              assertThat(r.base()).isEqualTo("EUR");
              assertThat(r.rateDate()).isEqualTo(LocalDate.of(2026, 11, 3));
              assertThat(r.runId()).isEqualTo("run-1");
            });
    // Exactly as published: compareTo, because 0.83358 must not become 0.83358000000000001.
    assertThat(sink.rateOf("GBP")).isEqualByComparingTo("0.83358");
    assertThat(sink.rateOf("USD")).isEqualByComparingTo("1.1179");
  }

  @Test
  void htmlRecipeReadsTheSameNumbersAsTheXml() throws Exception {
    Collecting xml = new Collecting();
    new EcbEurofxrefXmlExtractor()
        .extract(
            spec("x", SourceSpec.Format.ECB_EUROFXREF_XML, null),
            fixture("ecb-eurofxref-2026-11-03.xml"),
            CTX,
            xml);
    Collecting html = new Collecting();
    new HtmlRateTableExtractor()
        .extract(
            spec("h", SourceSpec.Format.HTML_RATE_TABLE, HTML_RECIPE),
            fixture("ecb-reference-2026-11-03.html"),
            CTX,
            html);

    assertThat(html.rates).isNotEmpty();
    for (ReferenceRateObserved r : html.rates) {
      assertThat(r.rateDate()).isEqualTo(LocalDate.of(2026, 11, 3));
      assertThat(r.rate()).as(r.quote()).isEqualByComparingTo(xml.rateOf(r.quote()));
    }
  }

  @Test
  void aSelectorThatNoLongerMatchesIsNamedInTheError() {
    SourceSpec.Recipe stale =
        new SourceSpec.Recipe(1, new java.util.HashMap<>(HTML_RECIPE.settings()));
    stale.settings().put("rowSelector", "table.rates-v2 tr");

    assertThatThrownBy(
            () ->
                new HtmlRateTableExtractor()
                    .extract(
                        spec("h", SourceSpec.Format.HTML_RATE_TABLE, stale),
                        fixture("ecb-reference-2026-11-03.html"),
                        CTX,
                        new Collecting()))
        .isInstanceOf(ExtractionException.class)
        .hasMessageContaining("table.rates-v2 tr");
  }

  @Test
  void sdmxCsvFindsColumnsByName() throws Exception {
    Collecting sink = new Collecting();
    int n =
        new EcbSdmxCsvExtractor()
            .extract(
                spec("s", SourceSpec.Format.ECB_SDMX_CSV, null),
                fixture("ecb-sdmx-2016-01.csv"),
                CTX,
                sink);

    assertThat(n).isEqualTo(39);
    ReferenceRateObserved first = sink.rates.get(0);
    assertThat(first.base()).isEqualTo("EUR");
    assertThat(first.quote()).isEqualTo("ARS");
    assertThat(first.rateDate()).isEqualTo(LocalDate.of(2016, 1, 4));
    assertThat(first.rate()).isEqualByComparingTo("14.2519");
  }

  @Test
  void sdmxCsvWithAMissingColumnSaysWhich(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("bad.csv");
    Files.writeString(file, "KEY,CURRENCY,TIME_PERIOD,VALUE\nX,USD,2026-11-03,1.1\n");

    assertThatThrownBy(
            () ->
                new EcbSdmxCsvExtractor()
                    .extract(
                        spec("s", SourceSpec.Format.ECB_SDMX_CSV, null),
                        file,
                        CTX,
                        new Collecting()))
        .isInstanceOf(ExtractionException.class)
        .hasMessageContaining("CURRENCY_DENOM");
  }
}
