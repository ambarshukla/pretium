package io.pretium.collector.repair;

import io.pretium.collector.extract.ExtractionException;
import io.pretium.collector.extract.Extractor;
import io.pretium.collector.extract.HtmlRateTableExtractor;
import io.pretium.collector.extract.ObservationSink;
import io.pretium.collector.source.SourceSpec;
import io.pretium.events.ProviderQuoteObserved;
import io.pretium.events.ReferenceRateObserved;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Decides whether a recipe reads a page correctly, by comparing everything it reads with the
 * structured channel for the same day.
 *
 * <p>A recipe is accepted only if it reads the page's date, finds at least 80% of the currencies
 * the reference has for that day, and every rate it reads equals the reference exactly. "Exactly"
 * because reference rates are published to fixed decimals: a near miss is a wrong number.
 */
public final class RecipeVerifier {

  /** The outcome of applying one recipe to one page. */
  public record Verdict(
      boolean accepted,
      LocalDate date,
      int read,
      int expected,
      int matched,
      List<String> wrong,
      String error,
      List<Reading> readings) {

    /** Read numbers that differ from the truth: what an unverified repair would have published. */
    public int wrongCount() {
      return wrong.size();
    }
  }

  /**
   * One currency: what the recipe read and what the reference says. {@code read} is null when the
   * recipe did not find the currency, {@code reference} when the reference has no rate for it.
   */
  public record Reading(String currency, BigDecimal read, BigDecimal reference) {

    public boolean matches() {
      return read != null && reference != null && read.compareTo(reference) == 0;
    }
  }

  private final ReferenceTruth truth;

  public ReferenceTruth truth() {
    return truth;
  }

  public RecipeVerifier(ReferenceTruth truth) {
    this.truth = truth;
  }

  public Verdict verify(Path page, SourceSpec.Recipe recipe) {
    SourceSpec spec =
        new SourceSpec(
            "candidate",
            "candidate",
            "candidate",
            "candidate",
            null,
            "https://localhost/",
            null,
            SourceSpec.Format.HTML_RATE_TABLE,
            "0 0 0 * * *",
            "UTC",
            recipe);
    List<ReferenceRateObserved> read = new ArrayList<>();
    try {
      new HtmlRateTableExtractor()
          .extract(
              spec,
              page,
              new Extractor.Context("verify", "verify"),
              new ObservationSink() {
                @Override
                public void rate(ReferenceRateObserved r) {
                  read.add(r);
                }

                @Override
                public void quote(ProviderQuoteObserved quote) {}
              });
    } catch (ExtractionException | RuntimeException e) {
      return new Verdict(false, null, 0, 0, 0, List.of(), e.getMessage(), List.of());
    }

    LocalDate date = read.get(0).rateDate();
    Map<String, BigDecimal> expected = truth.on(date);
    if (expected.isEmpty()) {
      return new Verdict(
          false,
          date,
          read.size(),
          0,
          0,
          List.of(),
          "no reference rates for " + date,
          readings(read, expected));
    }
    int matched = 0;
    List<String> wrong = new ArrayList<>();
    for (ReferenceRateObserved r : read) {
      BigDecimal truthRate = expected.get(truth.rowCurrency(r));
      if (truthRate != null && truthRate.compareTo(r.rate()) == 0) {
        matched++;
      } else {
        wrong.add(truth.rowCurrency(r) + "=" + r.rate() + " (reference " + truthRate + ")");
      }
    }
    // 80%, not 100%: the SDMX API also carries indicative rates (ARS, DZD, MAD, TWD in 2017)
    // that never appear on the reference page. 31 of 35 in January 2017 is complete.
    boolean coverage = matched * 10 >= expected.size() * 8;
    boolean accepted = wrong.isEmpty() && coverage;
    String error =
        accepted
            ? null
            : !wrong.isEmpty()
                ? wrong.size() + " values differ from the reference"
                : "read " + matched + " of " + expected.size() + " currencies";
    return new Verdict(
        accepted,
        date,
        read.size(),
        expected.size(),
        matched,
        List.copyOf(wrong),
        error,
        readings(read, expected));
  }

  /** Every currency either side has, in alphabetical order. */
  private List<Reading> readings(
      List<ReferenceRateObserved> read, Map<String, BigDecimal> expected) {
    Map<String, BigDecimal> byCurrency = new TreeMap<>();
    for (ReferenceRateObserved r : read) {
      byCurrency.putIfAbsent(truth.rowCurrency(r), r.rate());
    }
    TreeSet<String> currencies = new TreeSet<>(byCurrency.keySet());
    currencies.addAll(expected.keySet());
    List<Reading> out = new ArrayList<>();
    for (String c : currencies) {
      out.add(new Reading(c, byCurrency.get(c), expected.get(c)));
    }
    return List.copyOf(out);
  }
}
