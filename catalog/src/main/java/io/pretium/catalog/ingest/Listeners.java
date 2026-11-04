package io.pretium.catalog.ingest;

import io.pretium.catalog.reconcile.Reconciler;
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
  private final Reconciler reconciler;
  private final JsonMapper json;

  public Listeners(CatalogStore store, Reconciler reconciler, JsonMapper json) {
    this.store = store;
    this.reconciler = reconciler;
    this.json = json;
  }

  @KafkaListener(topics = Topics.COLLECTION_RUNS)
  public void run(String message) {
    CollectionRun run = json.readValue(message, CollectionRun.class);
    store.saveRun(run);
  }

  @KafkaListener(topics = Topics.REFERENCE_RATES, batch = "true")
  public void rates(List<String> messages) {
    List<ReferenceRateObserved> rates =
        messages.stream().map(m -> json.readValue(m, ReferenceRateObserved.class)).toList();
    store.saveRates(rates);
    Set<LocalDate> dates = new TreeSet<>();
    rates.forEach(r -> dates.add(r.rateDate()));
    reconciler.reconcile(dates);
  }

  @KafkaListener(topics = Topics.PROVIDER_QUOTES, batch = "true")
  public void quotes(List<String> messages) {
    List<ProviderQuoteObserved> quotes =
        messages.stream().map(m -> json.readValue(m, ProviderQuoteObserved.class)).toList();
    int quarantined = store.saveQuotes(quotes);
    if (quarantined > 0) {
      log.info("batch of {} quotes: {} prices quarantined", quotes.size(), quarantined);
    }
  }
}
