package io.pretium.collector.repair;

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
 * What the ECB's structured channel says each rate was, by day. This is the oracle a repaired
 * scraper recipe is judged against: a recipe is only right if it reads exactly these numbers.
 */
public final class ReferenceTruth {

  private final Map<LocalDate, Map<String, BigDecimal>> byDate = new HashMap<>();

  private ReferenceTruth() {}

  /** Loads an SDMX CSV evidence file (the history backfill holds every day since 2016). */
  public static ReferenceTruth fromSdmxCsv(Path file) throws ExtractionException {
    return fromSdmxCsv(List.of(file));
  }

  /** Loads several SDMX CSV evidence files into one: the history backfill plus later runs. */
  public static ReferenceTruth fromSdmxCsv(List<Path> files) throws ExtractionException {
    ReferenceTruth truth = new ReferenceTruth();
    for (Path file : files) {
      truth.load(file);
    }
    return truth;
  }

  private void load(Path file) throws ExtractionException {
    ReferenceTruth truth = this;
    SourceSpec spec =
        new SourceSpec(
            "truth",
            "truth",
            "ECB",
            "ECB",
            null,
            "https://data-api.ecb.europa.eu/",
            null,
            SourceSpec.Format.ECB_SDMX_CSV,
            "0 0 0 * * *",
            "UTC",
            null);
    new EcbSdmxCsvExtractor()
        .extract(
            spec,
            file,
            new Extractor.Context("truth", "truth"),
            new ObservationSink() {
              @Override
              public void rate(ReferenceRateObserved r) {
                truth
                    .byDate
                    .computeIfAbsent(r.rateDate(), d -> new HashMap<>())
                    .put(r.quote(), r.rate());
              }

              @Override
              public void quote(ProviderQuoteObserved quote) {}
            });
  }

  /** The rates for one day, by currency; empty if the ECB published none that day. */
  public Map<String, BigDecimal> on(LocalDate date) {
    return byDate.getOrDefault(date, Map.of());
  }
}
