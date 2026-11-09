package io.pretium.collector.repair;

import io.pretium.collector.source.SourceSpec;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The cheapest repair there is: keep the recipe's table selectors and replace only its date
 * settings with what the date finder saw on the page.
 *
 * <p>Pages often change one thing at a time. When only the line announcing the date was reworded,
 * the table selectors are still right, and asking a model (about 75 seconds on a laptop) for them
 * again only gives it a chance to get them wrong. This runs in microseconds and is checked by the
 * same verifier as any other proposal.
 */
public final class DateOnlyRepair {

  private DateOnlyRepair() {}

  /**
   * The recipe with its date settings replaced, or null when the finder found no date or the
   * recipe's date settings are already exactly those (nothing would change).
   */
  public static SourceSpec.Recipe apply(
      SourceSpec.Recipe current, PageDigest.DateHint date, int version) {
    if (date == null) {
      return null;
    }
    Map<String, String> settings = new LinkedHashMap<>(current.settings());
    settings.put("dateSelector", date.selector());
    settings.put("dateFormat", date.format());
    if (date.regex() == null) {
      settings.remove("dateRegex");
    } else {
      settings.put("dateRegex", date.regex());
    }
    if (settings.equals(current.settings())) {
      return null;
    }
    return new SourceSpec.Recipe(version, settings);
  }
}
