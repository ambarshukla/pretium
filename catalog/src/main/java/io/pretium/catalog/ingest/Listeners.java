package io.pretium.catalog.ingest;

import io.micrometer.core.instrument.MeterRegistry;
import io.pretium.catalog.api.FreshnessProperties;
import io.pretium.catalog.reconcile.SettledReconciliation;
import io.pretium.catalog.store.CatalogStore;
import io.pretium.events.CollectionRun;
import io.pretium.events.ProviderQuoteObserved;
import io.pretium.events.ReferenceRateObserved;
import io.pretium.events.Topics;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Kafka consumers. Each takes a batch of messages, writes the batch in one transaction and returns;
 * the offsets are committed only after that, so a crash mid-batch means the batch is delivered
 * again, and the upserts make the second delivery harmless.
 */
@Component
public class Listeners {

  private static final Logger log = LoggerFactory.getLogger(Listeners.class);

  private final CatalogStore store;
  private final SettledReconciliation reconciliation;
  private final JsonMapper json;
  private final MeterRegistry meters;

  public Listeners(
      CatalogStore store,
      SettledReconciliation reconciliation,
      JsonMapper json,
      MeterRegistry meters,
      FreshnessProperties sources) {
    this.meters = meters;
    // Created at zero, so that Prometheus sees the first increment after a restart (it computes
    // increases between two samples, and a counter that first appears at 1 has no earlier one).
    for (String name :
        List.of(
            "pretium.catalog.rates.stored",
            "pretium.catalog.quotes.stored",
            "pretium.catalog.prices.stored",
            "pretium.catalog.prices.quarantined")) {
      meters.counter(name);
    }
    for (String source : sources.freshness().keySet()) {
      for (CollectionRun.Status status : CollectionRun.Status.values()) {
        meters.counter("pretium.catalog.runs.stored", "source", source, "status", status.name());
      }
    }
    this.store = store;
    this.reconciliation = reconciliation;
    this.json = json;
  }

  @KafkaListener(topics = Topics.COLLECTION_RUNS)
  public void run(String message) {
    CollectionRun run = read(message, CollectionRun.class, Topics.COLLECTION_RUNS);
    if (run == null) {
      return;
    }
    store.saveRun(run);
    meters
        .counter(
            "pretium.catalog.runs.stored", "source", run.sourceId(), "status", run.status().name())
        .increment();
  }

  /**
   * One message as its record, or null if it cannot be read as one. A message that is not valid
   * JSON for its topic will never become valid however often it is retried, so it is counted and
   * skipped on its own; the rest of its batch is still stored. (A database failure, by contrast,
   * throws out of the listener and the whole batch is retried, which is what a transient fault
   * needs.)
   */
  <T> T read(String message, Class<T> type, String topic) {
    try {
      return json.readValue(message, type);
    } catch (RuntimeException e) {
      meters.counter("pretium.catalog.messages.unreadable", "topic", topic).increment();
      log.error(
          "{}: unreadable message skipped ({}): {}",
          topic,
          e.getMessage(),
          message.length() > 200 ? message.substring(0, 200) + "..." : message);
      return null;
    }
  }

  private <T> List<T> readAll(List<String> messages, Class<T> type, String topic) {
    return messages.stream()
        .map(m -> read(m, type, topic))
        .filter(java.util.Objects::nonNull)
        .toList();
  }

  @KafkaListener(topics = Topics.REFERENCE_RATES, batch = "true")
  public void rates(List<String> messages) {
    List<ReferenceRateObserved> rates =
        readAll(messages, ReferenceRateObserved.class, Topics.REFERENCE_RATES);
    store.saveRates(rates);
    meters.counter("pretium.catalog.rates.stored").increment(rates.size());
    Set<LocalDate> dates = new TreeSet<>();
    rates.forEach(r -> dates.add(r.rateDate()));
    reconciliation.touched(dates);
  }

  @KafkaListener(topics = Topics.PROVIDER_QUOTES, batch = "true")
  public void quotes(List<String> messages) {
    List<ProviderQuoteObserved> quotes =
        readAll(messages, ProviderQuoteObserved.class, Topics.PROVIDER_QUOTES);
    int quarantined = store.saveQuotes(quotes);
    meters.counter("pretium.catalog.quotes.stored").increment(quotes.size());
    // A quote carries one price per amount tier (200 and 500 USD), and checks judge each price, so
    // the quarantine rate is quarantined prices over stored prices, not over quotes.
    meters
        .counter("pretium.catalog.prices.stored")
        .increment(quotes.stream().mapToInt(q -> q.tiers().size()).sum());
    meters.counter("pretium.catalog.prices.quarantined").increment(quarantined);
    if (quarantined > 0) {
      log.info("batch of {} quotes: {} prices quarantined", quotes.size(), quarantined);
    }
  }
}
