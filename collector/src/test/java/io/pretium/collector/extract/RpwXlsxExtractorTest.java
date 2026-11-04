package io.pretium.collector.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pretium.collector.source.SourceSpec;
import io.pretium.events.ProviderQuoteObserved;
import io.pretium.events.ReferenceRateObserved;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reads a small workbook laid out exactly like the World Bank's, holding three real rows copied
 * verbatim from the Q2 2016 survey (ids 620160001, 620160003 and 620160004). The full file is 50
 * MB, so it is not a fixture; these rows cover a transparent provider, an opaque one and a
 * cross-region corridor.
 */
class RpwXlsxExtractorTest {

  static final String SHEET = "Dataset (from Q2 2016)";

  // The real header, in the real order, including columns the extractor does not use.
  static final String[] HEADER = {
    "id",
    "period",
    "source_code",
    "source_name",
    "source_region",
    "source_income",
    "source_lending",
    "source_G8G20",
    "destination_code",
    "destination_name",
    "destination_region",
    "destination_income",
    "destination_lending",
    "destination_G8G20",
    "firm",
    "firm_type",
    "payment instrument",
    "access point",
    "speed actual",
    "cc1 lcu amount",
    "cc1 denomination amount",
    "cc1 lcu code",
    "cc1 lcu fee",
    "cc1 lcu fx rate",
    "cc1 fx margin",
    "cc1 total cost %",
    "cc2 lcu amount",
    "cc2 denomination amount",
    "cc2 lcu code",
    "cc2 lcu fee",
    "cc2 lcu fx rate",
    "cc2 fx margin",
    "cc2 total cost %",
    "inter lcu bank fx",
    "transparent",
    "Standard Note",
    "note2",
    "receiving network coverage",
    "pickup location",
    "pickup method",
    "date",
    "corridor"
  };

  static final Object[][] ROWS = {
    {
      "620160001",
      "2016_2Q",
      "AGO",
      "Angola",
      "Sub-Saharan Africa",
      "Lower middle income",
      "IBRD",
      "..",
      "NAM",
      "Namibia",
      "Sub-Saharan Africa",
      "Upper middle income",
      "IBRD",
      "..",
      "MoneyGram",
      "Money Transfer Operator",
      "Cash",
      "Agent",
      "Less than one hour",
      33000,
      200,
      "AOA",
      2900,
      0.084,
      7.49,
      16.28,
      83000,
      500,
      "AOA",
      5700,
      0.084,
      7.49,
      14.36,
      0.0908,
      "yes",
      "",
      "",
      "High",
      "Agent",
      "Cash",
      "11/May/2016",
      "AGONAM"
    },
    {
      "620160003",
      "2016_2Q",
      "AGO",
      "Angola",
      "Sub-Saharan Africa",
      "Lower middle income",
      "IBRD",
      "..",
      "NAM",
      "Namibia",
      "Sub-Saharan Africa",
      "Upper middle income",
      "IBRD",
      "..",
      "Standard Bank",
      "Bank",
      "Bank account transfer",
      "Bank branch",
      "Next day",
      33000,
      200,
      "AOA",
      9075,
      1,
      0,
      27.5,
      83000,
      500,
      "AOA",
      9075,
      1,
      0,
      10.93,
      1,
      "no",
      "This RSP is not transparent",
      "",
      "Low",
      "",
      "Bank account",
      "11/May/2016",
      "AGONAM"
    },
    {
      "620160004",
      "2016_2Q",
      "ARE",
      "United Arab Emirates",
      "..",
      "High income",
      "..",
      "..",
      "BGD",
      "Bangladesh",
      "South Asia",
      "Lower middle income",
      "IDA",
      "..",
      "Al Ansari",
      "Money Transfer Operator",
      "Cash",
      "Agent",
      "Less than one hour",
      735,
      200,
      "AED",
      15,
      21.32,
      0.08,
      2.13,
      1835,
      500,
      "AED",
      20,
      21.32,
      0.08,
      1.17,
      21.338,
      "yes",
      "",
      "",
      "High",
      "Agent",
      "Cash",
      "09/May/2016",
      "AREBGD"
    },
  };

  static Path workbook(Path dir, String[] header, Object[][] rows) throws Exception {
    Path file = dir.resolve("rpw.xlsx");
    try (OutputStream out = Files.newOutputStream(file)) {
      Workbook wb = new Workbook(out, "test", "1.0");
      Worksheet ws = wb.newWorksheet(SHEET);
      for (int c = 0; c < header.length; c++) {
        ws.value(0, c, header[c]);
      }
      for (int r = 0; r < rows.length; r++) {
        for (int c = 0; c < rows[r].length; c++) {
          Object v = rows[r][c];
          if (v instanceof Number n) {
            ws.value(r + 1, c, n);
          } else if (!"".equals(v)) {
            ws.value(r + 1, c, (String) v);
          }
        }
      }
      wb.finish();
    }
    return file;
  }

  static SourceSpec spec() {
    return new SourceSpec(
        "wb-rpw",
        "rpw",
        "World Bank",
        "CC-BY 4.0",
        null,
        "https://example.org/rpw.xlsx",
        null,
        SourceSpec.Format.RPW_XLSX,
        "0 0 0 * * *",
        "UTC",
        new SourceSpec.Recipe(1, Map.of("sheet", SHEET)));
  }

  static class Quotes implements ObservationSink {
    final List<ProviderQuoteObserved> quotes = new ArrayList<>();

    @Override
    public void rate(ReferenceRateObserved rate) {
      throw new AssertionError("RPW produces no reference rates");
    }

    @Override
    public void quote(ProviderQuoteObserved quote) {
      quotes.add(quote);
    }
  }

  @Test
  void readsEveryRowWithBothSurveyedAmounts(@TempDir Path dir) throws Exception {
    Quotes sink = new Quotes();
    int n =
        new RpwXlsxExtractor()
            .extract(spec(), workbook(dir, HEADER, ROWS), new Extractor.Context("r", "s"), sink);

    assertThat(n).isEqualTo(3);
    ProviderQuoteObserved moneygram = sink.quotes.get(0);
    assertThat(moneygram.sourceRecordId()).isEqualTo("620160001");
    assertThat(moneygram.provider()).isEqualTo("MoneyGram");
    assertThat(moneygram.sendCountry()).isEqualTo("AGO");
    assertThat(moneygram.receiveCountry()).isEqualTo("NAM");
    assertThat(moneygram.transparent()).isTrue();
    assertThat(moneygram.observedOn()).isEqualTo(LocalDate.of(2016, 5, 11));
    assertThat(moneygram.interbankRate()).isEqualByComparingTo("0.0908");
    assertThat(moneygram.tiers()).hasSize(2);

    ProviderQuoteObserved.Tier usd200 = moneygram.tiers().get(0);
    assertThat(usd200.amountUsd()).isEqualTo(200);
    assertThat(usd200.sendCurrency()).isEqualTo("AOA");
    assertThat(usd200.fee()).isEqualByComparingTo("2900");
    // Excel stores 0.084 as 8.4000000000000005E-2; it must come back as the 0.084 that was typed.
    assertThat(usd200.fxRate()).isEqualByComparingTo("0.084");
    assertThat(usd200.totalCostPct()).isEqualByComparingTo("16.28");

    assertThat(sink.quotes.get(1).transparent()).isFalse();
  }

  @Test
  void aRenamedColumnFailsTheRunAndNamesTheColumn(@TempDir Path dir) throws Exception {
    String[] renamed = HEADER.clone();
    renamed[22] = "cc1 fee (lcu)";

    assertThatThrownBy(
            () ->
                new RpwXlsxExtractor()
                    .extract(
                        spec(),
                        workbook(dir, renamed, ROWS),
                        new Extractor.Context("r", "s"),
                        new Quotes()))
        .isInstanceOf(ExtractionException.class)
        .hasMessageContaining("cc1 lcu fee");
  }

  @Test
  void datesInBothForms() {
    assertThat(RpwXlsxExtractor.date("11/May/2016")).isEqualTo(LocalDate.of(2016, 5, 11));
    assertThat(RpwXlsxExtractor.date("42501")).isEqualTo(LocalDate.of(2016, 5, 11));
    assertThat(RpwXlsxExtractor.date("sometime in May")).isNull();
  }
}
