package io.pretium.catalog.reconcile;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reconciles a date only once its rates have stopped arriving.
 *
 * <p>Reconciling after every Kafka batch produced false breaks: the rates topic has several
 * partitions, so one day's currencies from one channel arrive over several batches, and in between
 * a channel looks as if it lacks currencies it has in fact sent. (The first hosted load raised 14
 * such breaks; all closed themselves.) Now each batch only marks its dates as touched, and a date
 * is reconciled when nothing has touched it for a quiet period.
 */
@Component
public class SettledReconciliation {

  private final Reconciler reconciler;
  private final Duration quiet;
  private final Clock clock;
  private final Map<LocalDate, Instant> touched = new ConcurrentHashMap<>();

  @Autowired
  public SettledReconciliation(
      Reconciler reconciler, @Value("${pretium.reconcile.quiet-period:30s}") Duration quiet) {
    this(reconciler, quiet, Clock.systemUTC());
  }

  SettledReconciliation(Reconciler reconciler, Duration quiet, Clock clock) {
    this.reconciler = reconciler;
    this.quiet = quiet;
    this.clock = clock;
  }

  /** Called for every batch of rates: remember which dates it touched, and when. */
  public void touched(Iterable<LocalDate> dates) {
    Instant now = clock.instant();
    dates.forEach(d -> touched.put(d, now));
  }

  /** Reconciles every date that has been quiet for long enough. */
  @Scheduled(fixedDelayString = "${pretium.reconcile.check-every:10s}")
  public void reconcileSettled() {
    Instant cutoff = clock.instant().minus(quiet);
    List<LocalDate> settled = new ArrayList<>();
    touched.forEach(
        (date, at) -> {
          if (at.isBefore(cutoff)) {
            settled.add(date);
          }
        });
    if (settled.isEmpty()) {
      return;
    }
    reconciler.reconcile(settled);
    // Remove only if not touched again while reconciling; otherwise it waits for the next round.
    settled.forEach(
        date -> touched.computeIfPresent(date, (d, at) -> at.isBefore(cutoff) ? null : at));
  }

  int pending() {
    return touched.size();
  }
}
