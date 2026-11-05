package io.pretium.catalog.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import io.pretium.catalog.api.FreshnessProperties;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publishes, for every source, how old its last good collection is and whether that is within the
 * freshness target in {@code application.yml}: the service level indicator behind docs/SLO.md.
 *
 * <p>The database is read once a minute rather than on every scrape, so a busy dashboard cannot
 * load Postgres. Gauges: {@code pretium_source_age_seconds}, {@code pretium_source_fresh} (1 or 0)
 * and {@code pretium_source_freshness_target_seconds}, each tagged with the source.
 */
@Component
public class FreshnessMetrics {

  private final JdbcTemplate jdbc;
  private final FreshnessProperties freshness;
  private final MultiGauge age;
  private final MultiGauge fresh;
  private final MultiGauge target;

  public FreshnessMetrics(JdbcTemplate jdbc, FreshnessProperties freshness, MeterRegistry meters) {
    this.jdbc = jdbc;
    this.freshness = freshness;
    this.age =
        MultiGauge.builder("pretium.source.age.seconds").baseUnit("seconds").register(meters);
    this.fresh = MultiGauge.builder("pretium.source.fresh").register(meters);
    this.target =
        MultiGauge.builder("pretium.source.freshness.target.seconds")
            .baseUnit("seconds")
            .register(meters);
  }

  @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT15S")
  public void refresh() {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            """
            select source_id as "sourceId", max(finished_at) as "lastGoodAt"
              from collection_run
             where status in ('SUCCEEDED', 'UNCHANGED')
             group by source_id
            """);
    List<MultiGauge.Row<?>> ages = new ArrayList<>();
    List<MultiGauge.Row<?>> freshRows = new ArrayList<>();
    List<MultiGauge.Row<?>> targets = new ArrayList<>();
    Instant now = Instant.now();
    for (Map<String, Object> row : rows) {
      String source = (String) row.get("sourceId");
      Duration limit = freshness.freshness().getOrDefault(source, Duration.ofDays(2));
      Duration seen = Duration.between(((Timestamp) row.get("lastGoodAt")).toInstant(), now);
      Tags tags = Tags.of("source", source);
      ages.add(MultiGauge.Row.of(tags, seen.toSeconds()));
      freshRows.add(MultiGauge.Row.of(tags, seen.compareTo(limit) <= 0 ? 1 : 0));
      targets.add(MultiGauge.Row.of(tags, limit.toSeconds()));
    }
    age.register(ages, true);
    fresh.register(freshRows, true);
    target.register(targets, true);
  }
}
