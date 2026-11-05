package io.pretium.collector.run;

import io.pretium.collector.fetch.FetchResult;
import io.pretium.collector.fetch.PoliteFetcher;
import io.pretium.collector.source.SourceSpec;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Finds the current URL of a source that moves.
 *
 * <p>The World Bank publishes each quarter's remittance file at a new address, with a new resource
 * id in the path ({@code .../DR0095523/rpw_dataset_2011_2025_q3.xlsx}), so the next one cannot be
 * guessed. The old address keeps answering, so a fixed URL would go on reading the same old file,
 * every run "succeeding", and freshness would never notice. The data catalogue names the current
 * file; this asks it before each run.
 *
 * <p>Any problem (catalogue down, rate-limited, entry missing, a URL on another host) falls back to
 * the URL in the spec and is logged: a failed lookup must never stop a collection.
 */
@Component
public class UrlDiscovery {

  private static final Logger log = LoggerFactory.getLogger(UrlDiscovery.class);

  private final PoliteFetcher fetcher;
  private final JsonMapper json;

  public UrlDiscovery(PoliteFetcher fetcher, JsonMapper json) {
    this.fetcher = fetcher;
    this.json = json;
  }

  /** The URL to fetch on a normal run. */
  public String urlFor(SourceSpec spec) {
    SourceSpec.Discovery discovery = spec.discovery();
    if (discovery == null) {
      return spec.url();
    }
    FetchResult result = fetcher.fetch(discovery.catalogUrl(), Optional.empty());
    if (result.outcome() != FetchResult.Outcome.OK) {
      log.warn(
          "{}: catalogue lookup {} ({}), using {}",
          spec.id(),
          result.outcome(),
          result.message(),
          spec.url());
      return spec.url();
    }
    try {
      JsonNode root = json.readTree(Files.readString(result.body()));
      Optional<String> found = find(root, discovery.resourceName(), spec.url());
      if (found.isEmpty()) {
        log.warn(
            "{}: catalogue has no usable '{}', using {}",
            spec.id(),
            discovery.resourceName(),
            spec.url());
        return spec.url();
      }
      if (!found.get().equals(spec.url())) {
        log.info("{}: catalogue names a newer file: {}", spec.id(), found.get());
      }
      return found.get();
    } catch (IOException | JacksonException e) {
      log.warn("{}: catalogue unreadable ({}), using {}", spec.id(), e.toString(), spec.url());
      return spec.url();
    } finally {
      try {
        Files.deleteIfExists(result.body());
      } catch (IOException ignored) {
        // a leftover temporary file is harmless
      }
    }
  }

  /**
   * The URL of the named resource, if it is https and on the same host as the fallback URL. The
   * host check means a changed or tampered catalogue entry cannot send the collector elsewhere.
   */
  static Optional<String> find(JsonNode catalogue, String resourceName, String fallbackUrl) {
    String host = URI.create(fallbackUrl).getHost();
    for (JsonNode resource : catalogue.path("resources")) {
      if (!resourceName.equals(resource.path("name").asString(""))) {
        continue;
      }
      String url = resource.path("url").asString("");
      if (url.startsWith("https://") && host.equalsIgnoreCase(URI.create(url).getHost())) {
        return Optional.of(url);
      }
    }
    return Optional.empty();
  }
}
