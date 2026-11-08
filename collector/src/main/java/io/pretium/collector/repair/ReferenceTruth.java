package io.pretium.collector.repair;

import io.pretium.collector.extract.CnbFixingTxtExtractor;
import io.pretium.collector.extract.EcbSdmxCsvExtractor;
import io.pretium.collector.extract.ExtractionException;
import io.pretium.collector.extract.Extractor;
import io.pretium.collector.extract.ObservationSink;
import io.pretium.collector.source.SourceSpec;
import io.pretium.events.ProviderQuoteObserved;
import io.pretium.events.ReferenceRateObserved;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What a publisher's structured channel says each rate was, by day. This is the oracle a repaired
 * scraper recipe is judged against: a recipe is only right if it reads exactly these numbers.
 *
 * <p>Rates are keyed by the currency each row of the page is about: the quote for the ECB (EUR/USD
 * is the USD row), the base for the CNB (USD/CZK is the USD row). The home currency, the one every
 * row shares, is the other side.
 */
public final class ReferenceTruth {

  private final Map<LocalDate, Map<String, BigDecimal>> byDate = new HashMap<>();

  private ReferenceTruth() {}

  /**
   * Loads a publisher's structured channel: the ECB's SDMX CSV or the CNB's text files (daily or
   * yearly).
   */
  public static ReferenceTruth fromFiles(SourceSpec.Format format, List<Path> files)
      throws ExtractionException {
    return switch (format) {
      case ECB_SDMX_CSV -> fromSdmxCsv(files);
      case CNB_FIXING_TXT -> {
        ReferenceTruth truth = new ReferenceTruth();
        for (Path file : files) {
          truth.load(file, new CnbFixingTxtExtractor(), format);
        }
        yield truth;
      }
      default -> throw new IllegalArgumentException(format + " is not a reference channel");
    };
  }

  /** Each scraped page's structured channel: the format its answer key is read in. */
  private static final Map<String, SourceSpec.Format> CHANNEL_OF_PAGE =
      Map.of(
          "ecb-reference-html", SourceSpec.Format.ECB_SDMX_CSV,
          "cnb-fixing-html", SourceSpec.Format.CNB_FIXING_TXT);

  /**
   * The answer key for a scraped page, from files of its publisher's structured channel. {@code
   * paths} is a comma-separated list of files or directories (every file in a directory is read).
   */
  public static ReferenceTruth forPage(String pageSource, String paths)
      throws ExtractionException, java.io.IOException {
    SourceSpec.Format format = CHANNEL_OF_PAGE.get(pageSource);
    if (format == null) {
      throw new IllegalArgumentException("no structured channel known for " + pageSource);
    }
    List<Path> files = new java.util.ArrayList<>();
    for (String p : paths.split(",")) {
      Path path = Path.of(p.strip());
      if (java.nio.file.Files.isDirectory(path)) {
        try (var s = java.nio.file.Files.list(path)) {
          s.filter(java.nio.file.Files::isRegularFile).sorted().forEach(files::add);
        }
      } else {
        files.add(path);
      }
    }
    return fromFiles(format, files);
  }

  /** Loads an SDMX CSV evidence file (the history backfill holds every day since 2016). */
  public static ReferenceTruth fromSdmxCsv(Path file) throws ExtractionException {
    return fromSdmxCsv(List.of(file));
  }

  /** Loads several SDMX CSV evidence files into one: the history backfill plus later runs. */
  public static ReferenceTruth fromSdmxCsv(List<Path> files) throws ExtractionException {
    ReferenceTruth truth = new ReferenceTruth();
    for (Path file : files) {
      truth.load(file, new EcbSdmxCsvExtractor(), SourceSpec.Format.ECB_SDMX_CSV);
    }
    return truth;
  }

  private void load(Path file, Extractor extractor, SourceSpec.Format format)
      throws ExtractionException {
    ReferenceTruth truth = this;
    SourceSpec spec =
        new SourceSpec(
            "truth",
            "truth",
            "truth",
            "truth",
            null,
            "https://localhost/",
            null,
            format,
            "0 0 0 * * *",
            "UTC",
            null);
    extractor.extract(
        spec,
        file,
        new Extractor.Context("truth", "truth"),
        new ObservationSink() {
          @Override
          public void rate(ReferenceRateObserved r) {
            truth
                .byDate
                .computeIfAbsent(r.rateDate(), d -> new HashMap<>())
                .put(rowCurrency(r), r.rate());
          }

          @Override
          public void quote(ProviderQuoteObserved quote) {}
        });
  }

  /**
   * The currency a row of a rates table is about: the side that is not the home currency (EUR for
   * the ECB, CZK for the CNB).
   */
  public static String rowCurrency(ReferenceRateObserved r) {
    return r.quote().equals(CnbFixingTxtExtractor.HOME) ? r.base() : r.quote();
  }

  /** The rates for one day, by currency; empty if the publisher published none that day. */
  public Map<String, BigDecimal> on(LocalDate date) {
    return byDate.getOrDefault(date, Map.of());
  }
}
