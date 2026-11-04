package io.pretium.catalog.api;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How old each source's last good collection may be before it counts as stale: the freshness
 * service level objective (SLO), per source. Set in {@code application.yml} under {@code
 * pretium.freshness}.
 */
@ConfigurationProperties(prefix = "pretium")
public record FreshnessProperties(Map<String, Duration> freshness) {

  public FreshnessProperties {
    freshness = freshness == null ? Map.of() : Map.copyOf(freshness);
  }
}
