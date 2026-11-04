package io.pretium.catalog.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;

class SettledReconciliationTest {

  /** A clock the test moves by hand. */
  static final class TestClock extends Clock {
    Instant now = Instant.parse("2026-11-04T15:20:00Z");

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  /** Records what it was asked to reconcile instead of touching a database. */
  static final class Recording extends Reconciler {
    final List<LocalDate> reconciled = new ArrayList<>();

    Recording() {
      super(null, "ecb-sdmx-exr");
    }

    @Override
    public void reconcile(Collection<LocalDate> dates) {
      reconciled.addAll(dates);
    }
  }

  static final LocalDate DAY = LocalDate.of(2026, 11, 4);

  @Test
  void aDateIsReconciledOnlyAfterItsRatesStopArriving() {
    TestClock clock = new TestClock();
    Recording reconciler = new Recording();
    SettledReconciliation settled =
        new SettledReconciliation(reconciler, Duration.ofSeconds(30), clock);

    settled.touched(List.of(DAY)); // first partition's batch
    clock.now = clock.now.plusSeconds(20);
    settled.reconcileSettled();
    assertThat(reconciler.reconciled).isEmpty();

    settled.touched(List.of(DAY)); // second partition's batch: the clock restarts
    clock.now = clock.now.plusSeconds(20);
    settled.reconcileSettled();
    assertThat(reconciler.reconciled).isEmpty();

    clock.now = clock.now.plusSeconds(11);
    settled.reconcileSettled();
    assertThat(reconciler.reconciled).containsExactly(DAY);
    assertThat(settled.pending()).isZero();
  }
}
