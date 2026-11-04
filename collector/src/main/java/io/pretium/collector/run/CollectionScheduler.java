package io.pretium.collector.run;

import io.pretium.collector.source.SourceCatalog;
import io.pretium.collector.source.SourceSpec;
import java.time.ZoneId;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

/**
 * Puts every source on its own schedule, from the {@code cron} and {@code zone} in its spec.
 *
 * <p>Runs execute on a small dedicated pool, not on the scheduler's thread, so a 50 MB download
 * never delays the next source's start time.
 */
@Component
public class CollectionScheduler {

  private static final Logger log = LoggerFactory.getLogger(CollectionScheduler.class);

  private final SourceCatalog catalog;
  private final CollectionService service;
  private final TaskScheduler scheduler;
  private final boolean collectOnStartup;
  private final Executor workers = Executors.newFixedThreadPool(2);

  public CollectionScheduler(
      SourceCatalog catalog,
      CollectionService service,
      TaskScheduler scheduler,
      @Value("${pretium.collect-on-startup:false}") boolean collectOnStartup) {
    this.catalog = catalog;
    this.service = service;
    this.scheduler = scheduler;
    this.collectOnStartup = collectOnStartup;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void start() {
    for (SourceSpec spec : catalog.all()) {
      ZoneId zone = ZoneId.of(spec.zone() == null ? "UTC" : spec.zone());
      CronTrigger trigger = new CronTrigger(spec.cron(), zone);
      scheduler.schedule(() -> submit(spec), trigger);
      log.info("scheduled {} at '{}' ({})", spec.id(), spec.cron(), zone);
      if (collectOnStartup) {
        submit(spec);
      }
    }
  }

  /** Starts a run in the background. Returns immediately. */
  public void submit(SourceSpec spec) {
    submit(spec, false);
  }

  public void submit(SourceSpec spec, boolean force) {
    workers.execute(
        () -> {
          try {
            service.collect(spec, force);
          } catch (RuntimeException e) {
            log.error("{}: run did not complete", spec.id(), e);
          }
        });
  }
}
