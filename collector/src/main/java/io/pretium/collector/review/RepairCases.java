package io.pretium.collector.review;

import io.pretium.collector.repair.Casebook;
import io.pretium.collector.source.SourceCatalog;
import io.pretium.collector.source.SourceSpec;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The repair casebooks that ship with the collector ({@code classpath:repair/*.json}): every page a
 * recipe could not read during an evaluation, and what was done about it. Read-only; what people
 * decide about each case goes to the {@link DecisionLog}.
 */
@Component
public class RepairCases {

  private final List<Casebook> casebooks;
  private final SourceCatalog sources;

  public RepairCases(SourceCatalog sources, JsonMapper json) throws IOException {
    this.sources = sources;
    this.casebooks = List.copyOf(load(json));
  }

  private static List<Casebook> load(JsonMapper json) throws IOException {
    List<Casebook> out = new ArrayList<>();
    Resource[] files =
        new PathMatchingResourcePatternResolver().getResources("classpath*:repair/*.json");
    for (Resource file : files) {
      try (InputStream in = file.getInputStream()) {
        out.add(json.readValue(in, Casebook.class));
      }
    }
    out.sort(Comparator.comparing(Casebook::source));
    return out;
  }

  public List<Casebook> all() {
    return casebooks;
  }

  public Optional<Casebook> casebook(String source) {
    return casebooks.stream().filter(c -> c.source().equals(source)).findFirst();
  }

  public Optional<Casebook.Case> find(String source, String snapshot) {
    return casebook(source)
        .flatMap(b -> b.cases().stream().filter(c -> c.snapshot().equals(snapshot)).findFirst());
  }

  /**
   * Recipes known to read some real version of the page: the one in use, then every recipe the
   * verifier accepted in an evaluation, by version. Rejected proposals are not in it.
   */
  public List<SourceSpec.Recipe> knownRecipes(String source) {
    Map<Integer, SourceSpec.Recipe> byVersion = new LinkedHashMap<>();
    sources.find(source).map(SourceSpec::recipe).ifPresent(r -> byVersion.put(r.version(), r));
    casebook(source)
        .ifPresent(
            b -> {
              for (Casebook.Case c : b.cases()) {
                Casebook.Attempt after = c.after();
                if (after != null && after.accepted()) {
                  byVersion.putIfAbsent(
                      after.recipeVersion(),
                      new SourceSpec.Recipe(after.recipeVersion(), after.recipe()));
                }
              }
            });
    return byVersion.values().stream()
        .sorted(Comparator.comparingInt(SourceSpec.Recipe::version))
        .toList();
  }
}
