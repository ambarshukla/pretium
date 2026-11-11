package io.pretium.collector.source;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Loads every {@code sources/*.yml} on the classpath and checks each one before the service starts.
 * A bad spec stops startup with a message naming the file, rather than failing later at 4pm when
 * the schedule fires.
 */
@Component
public class SourceCatalog {

  private final List<SourceSpec> specs;

  public SourceCatalog() throws IOException {
    this(new PathMatchingResourcePatternResolver().getResources("classpath*:sources/*.yml"));
  }

  SourceCatalog(Resource[] resources) throws IOException {
    YAMLMapper yaml = YAMLMapper.builder().build();
    List<SourceSpec> loaded = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    for (Resource resource : resources) {
      SourceSpec spec;
      try (InputStream in = resource.getInputStream()) {
        spec = yaml.readValue(in, SourceSpec.class);
      }
      validate(spec, resource.getFilename());
      if (!ids.add(spec.id())) {
        throw new IllegalStateException("duplicate source id '" + spec.id() + "'");
      }
      loaded.add(spec);
    }
    loaded.sort(Comparator.comparing(SourceSpec::id));
    this.specs = List.copyOf(loaded);
  }

  private static void validate(SourceSpec spec, String file) {
    List<String> problems = new ArrayList<>();
    if (spec.id() == null || !spec.id().matches("[a-z0-9-]+")) {
      problems.add("id must be lowercase letters, digits and hyphens");
    }
    if (spec.url() == null || !spec.url().startsWith("https://")) {
      problems.add("url must be https");
    }
    if (spec.format() == null) {
      problems.add("format is required");
    }
    if (spec.licence() == null || spec.licence().isBlank()) {
      problems.add("licence is required: no source is collected without stating its terms");
    }
    if (spec.cron() == null || !CronExpression.isValidExpression(spec.cron())) {
      problems.add("cron is not a valid cron expression");
    }
    if (spec.zone() != null) {
      try {
        java.time.ZoneId.of(spec.zone());
      } catch (java.time.DateTimeException e) {
        problems.add("zone '" + spec.zone() + "' is not a time zone id");
      }
    }
    // A scraped page is read by its recipe alone, so a missing selector would only be found at
    // the first scheduled run; a recipe is checked here for the keys the extractor requires.
    if (spec.format() == SourceSpec.Format.HTML_RATE_TABLE) {
      if (spec.recipe() == null) {
        problems.add("an HTML source needs a recipe");
      } else {
        for (String key :
            List.of(
                "dateSelector", "dateFormat", "rowSelector", "currencySelector", "rateSelector")) {
          if (spec.recipe().get(key, null) == null) {
            problems.add("recipe is missing '" + key + "'");
          }
        }
        if ((spec.recipe().get("base", null) == null)
            == (spec.recipe().get("quote", null) == null)) {
          problems.add("recipe needs exactly one of 'base' or 'quote'");
        }
      }
    }
    if (!problems.isEmpty()) {
      throw new IllegalStateException("source spec " + file + " is invalid: " + problems);
    }
  }

  public List<SourceSpec> all() {
    return specs;
  }

  public Optional<SourceSpec> find(String id) {
    return specs.stream().filter(s -> s.id().equals(id)).findFirst();
  }
}
