package io.pretium.collector.source;

import java.util.Map;

/**
 * Everything the collector needs to know about one source, read from a YAML file in {@code
 * sources/}. Adding a source of an existing format is a new file, not new code.
 *
 * @param id stable identifier, used in topics, the raw store and the database
 * @param name human-readable name
 * @param publisher who publishes the data
 * @param licence the licence or terms it is used under, and the attribution it requires
 * @param homepage where a person can read about the source
 * @param url what to fetch on a normal run
 * @param backfillUrl what to fetch the very first time (full history), or null to use {@code url}
 * @param format which extractor reads the response
 * @param cron when to collect, as a Spring cron expression (second minute hour day month weekday)
 * @param zone the time zone the cron expression is written in
 * @param recipe extractor settings: selectors for HTML, sheet name for spreadsheets
 */
public record SourceSpec(
    String id,
    String name,
    String publisher,
    String licence,
    String homepage,
    String url,
    String backfillUrl,
    Format format,
    String cron,
    String zone,
    Recipe recipe) {

  /** The response formats the collector can read. Each has one extractor. */
  public enum Format {
    ECB_EUROFXREF_XML,
    ECB_SDMX_CSV,
    HTML_RATE_TABLE,
    RPW_XLSX
  }

  /**
   * How to find the values inside a response. Versioned, because when a source changes its layout
   * the recipe changes with it, and every observation records which version read it.
   *
   * @param version incremented whenever the recipe changes
   * @param settings format-specific keys, e.g. {@code rowSelector} for HTML
   */
  public record Recipe(int version, Map<String, String> settings) {

    public String require(String key) {
      String value = settings == null ? null : settings.get(key);
      if (value == null || value.isBlank()) {
        throw new IllegalStateException("recipe is missing required setting '" + key + "'");
      }
      return value;
    }

    public String get(String key, String fallback) {
      String value = settings == null ? null : settings.get(key);
      return value == null || value.isBlank() ? fallback : value;
    }
  }

  public int recipeVersion() {
    return recipe == null ? 0 : recipe.version();
  }
}
