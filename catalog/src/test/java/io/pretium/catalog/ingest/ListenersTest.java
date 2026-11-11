package io.pretium.catalog.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pretium.catalog.api.FreshnessProperties;
import io.pretium.catalog.reconcile.SettledReconciliation;
import io.pretium.catalog.store.CatalogStore;
import io.pretium.events.ReferenceRateObserved;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * A Kafka message that is not what its topic promises can never be stored, however often it is
 * retried. It must be counted and skipped on its own, not take the rest of its batch down with it.
 */
class ListenersTest {

  static final JsonMapper JSON = JsonMapper.builder().build();

  final CatalogStore store = mock(CatalogStore.class);
  final SettledReconciliation reconciliation = mock(SettledReconciliation.class);
  final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  final Listeners listeners =
      new Listeners(store, reconciliation, JSON, meters, new FreshnessProperties(Map.of()));

  // A real ECB rate, as the collector publishes it.
  static final ReferenceRateObserved RATE =
      new ReferenceRateObserved(
          "5f1a8a3e-3f7c-4a5e-9a0a-3d5f8e3b1c2d",
          "ecb-sdmx-exr",
          "EUR",
          "USD",
          LocalDate.of(2026, 11, 3),
          new BigDecimal("1.1527"),
          "0".repeat(64));

  @Test
  void oneUnreadableMessageDoesNotDropTheRestOfItsBatch() {
    listeners.rates(List.of("this is not json", JSON.writeValueAsString(RATE), "{\"rate\": }"));

    verify(store).saveRates(argThat(rates -> rates.size() == 1 && rates.get(0).equals(RATE)));
    verify(reconciliation)
        .touched(argThat(dates -> dates.iterator().next().equals(RATE.rateDate())));
    assertThat(
            meters
                .get("pretium.catalog.messages.unreadable")
                .tag("topic", "pretium.reference-rates.v1")
                .counter()
                .count())
        .isEqualTo(2.0);
  }

  @Test
  void anUnreadableRunReportIsSkippedWithoutAStore() {
    listeners.run("{\"status\":\"NOT_A_STATUS\"}");

    verify(store, never()).saveRun(org.mockito.ArgumentMatchers.any());
    assertThat(meters.get("pretium.catalog.messages.unreadable").counter().count()).isEqualTo(1.0);
  }
}
