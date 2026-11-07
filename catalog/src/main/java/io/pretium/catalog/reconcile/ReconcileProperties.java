package io.pretium.catalog.reconcile;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Which channels publish the same numbers, set in {@code application.yml} under {@code
 * pretium.reconcile}.
 *
 * @param reference the main reference channel: the euro rates the benchmark and cross rates use
 * @param groups for each publisher, its reference channel and the channels checked against it. A
 *     channel is only ever compared with its own publisher's reference: the CNB's EUR/CZK and the
 *     ECB's EUR/CZK are fixed at different times of day, so their difference is not an error.
 */
@ConfigurationProperties(prefix = "pretium.reconcile")
public record ReconcileProperties(String reference, Map<String, List<String>> groups) {

  public ReconcileProperties {
    reference = reference == null ? "ecb-sdmx-exr" : reference;
    groups = groups == null ? Map.of() : Map.copyOf(groups);
  }
}
