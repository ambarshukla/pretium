package io.pretium.collector.review;

import io.pretium.collector.repair.Casebook;
import io.pretium.collector.repair.RecipeVerifier;
import io.pretium.collector.source.SourceCatalog;
import io.pretium.collector.source.SourceSpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Repair review and the parser lab.
 *
 * <ul>
 *   <li>{@code GET /api/v1/repairs} — every case, with the latest decision on each
 *   <li>{@code GET /api/v1/repairs/{source}/{snapshot}} — one case in full, with its decisions
 *   <li>{@code POST /api/v1/repairs/{source}/{snapshot}/decisions} — record a decision (key)
 *   <li>{@code GET /api/v1/lab} — pages, known recipes and models to run against them
 *   <li>{@code POST /api/v1/lab/runs} — run one parser against one page; nothing is stored
 *   <li>{@code POST /api/v1/lab/view} — one raw page: its source, a safe rendering with what a
 *       recipe matched outlined, and what a model would be shown and asked
 *   <li>{@code POST /api/v1/lab/batch} — one recipe over every page, judged by the verifier
 *   <li>{@code GET /api/v1/lab/bench} — models compared: repairing selectors against reading the
 *       numbers themselves (a result file made offline by {@code ModelBench})
 * </ul>
 *
 * <p>Anything that writes, runs a model, or runs a recipe a person typed needs the review key
 * ({@code X-Review-Key}). With no key configured, those are switched off and the rest is read-only.
 */
@RestController
public class ReviewController {

  private final RepairCases cases;
  private final DecisionLog decisions;
  private final Lab lab;
  private final SourceCatalog sources;
  private final byte[] key;

  public ReviewController(
      RepairCases cases,
      DecisionLog decisions,
      Lab lab,
      SourceCatalog sources,
      @Value("${pretium.review.key:}") String key) {
    this.cases = cases;
    this.decisions = decisions;
    this.lab = lab;
    this.sources = sources;
    this.key = key.isBlank() ? null : key.getBytes(StandardCharsets.UTF_8);
  }

  /** A case as listed: enough to scan the queue. */
  public record Summary(
      String source,
      String snapshot,
      String ratesDate,
      String outcome,
      String error,
      int proposals,
      int wrongNumbersBlocked,
      Integer fromVersion,
      Integer toVersion,
      DecisionLog.Decision decision) {}

  public record Listing(
      boolean reviewsEnabled,
      List<Summary> cases,
      Map<String, String> models,
      Map<String, String> generatedAt) {}

  public record Detail(
      String source,
      String pageUrl,
      String model,
      Casebook.Case repair,
      boolean pageAvailable,
      List<DecisionLog.Decision> decisions) {}

  public record DecisionRequest(
      DecisionLog.Action action, Map<String, String> recipe, String note) {}

  /**
   * What the lab can run. {@code inUse} is the recipe version the collector reads the live page
   * with; {@code cases} maps the archived pages that are repair cases to their outcome.
   */
  public record LabInfo(
      String source,
      String pageUrl,
      boolean keyRequiredForModelsAndCustomRecipes,
      boolean reviewsEnabled,
      List<Lab.Page> pages,
      List<SourceSpec.Recipe> recipes,
      int inUse,
      Map<String, String> cases,
      List<String> models) {}

  /** A page to show, with the recipe whose matches to outline: a known version, or one typed. */
  public record ViewRequest(String pageId, Integer recipeVersion, Map<String, String> recipe) {}

  /**
   * One run: a page, and exactly one of a known recipe version, a model, or a recipe a person
   * wrote.
   */
  public record RunRequest(
      String pageId, Integer recipeVersion, String model, Map<String, String> recipe) {}

  @GetMapping("/api/v1/repairs")
  public Listing list() throws IOException {
    Map<String, DecisionLog.Decision> latest = latestDecisions();
    Map<String, String> models = new LinkedHashMap<>();
    Map<String, String> generated = new LinkedHashMap<>();
    List<Summary> out =
        cases.all().stream()
            .peek(
                b -> {
                  models.put(b.source(), b.model());
                  generated.put(b.source(), b.generatedAt());
                })
            .flatMap(
                b ->
                    b.cases().stream()
                        .map(
                            c ->
                                new Summary(
                                    b.source(),
                                    c.snapshot(),
                                    c.ratesDate(),
                                    c.outcome(),
                                    c.before().error(),
                                    c.proposals().size(),
                                    c.proposals().stream()
                                        .mapToInt(
                                            a ->
                                                (int)
                                                    a.readings().stream()
                                                        .filter(
                                                            r ->
                                                                r.read() != null
                                                                    && r.reference() != null
                                                                    && !r.matches())
                                                        .count())
                                        .sum(),
                                    c.before().recipeVersion(),
                                    c.after() == null ? null : c.after().recipeVersion(),
                                    latest.get(b.source() + "/" + c.snapshot()))))
            .toList();
    return new Listing(key != null, out, models, generated);
  }

  @GetMapping("/api/v1/repairs/{source}/{snapshot}")
  public Detail detail(@PathVariable String source, @PathVariable String snapshot)
      throws IOException {
    Casebook book = cases.casebook(source).orElseThrow(() -> notFound("no such source"));
    Casebook.Case c = cases.find(source, snapshot).orElseThrow(() -> notFound("no such case"));
    List<DecisionLog.Decision> mine =
        decisions.all().stream()
            .filter(d -> d.source().equals(source) && d.snapshot().equals(snapshot))
            .toList();
    return new Detail(
        source, book.pageUrl(), book.model(), c, lab.archived(source, snapshot).isPresent(), mine);
  }

  @PostMapping("/api/v1/repairs/{source}/{snapshot}/decisions")
  @ResponseStatus(HttpStatus.CREATED)
  public DecisionLog.Decision decide(
      @PathVariable String source,
      @PathVariable String snapshot,
      @RequestHeader(value = "X-Review-Key", required = false) String given,
      @RequestBody DecisionRequest req)
      throws IOException {
    requireKey(given);
    Casebook.Case c = cases.find(source, snapshot).orElseThrow(() -> notFound("no such case"));
    if (req == null || req.action() == null) {
      throw bad("say what you decided: action is required");
    }
    String note = req.note() == null ? "" : req.note().strip();
    Integer matched = null;
    Integer expected = null;
    Map<String, String> recipe = null;
    switch (req.action()) {
      case CONFIRM_REPAIR -> {
        if (c.after() == null) {
          throw bad("there is no repair to confirm: nothing read this page");
        }
        recipe = c.after().recipe();
        matched = c.after().matched();
        expected = c.after().expected();
      }
      case REJECT_REPAIR -> {
        if (c.after() == null) {
          throw bad("there is no repair to reject");
        }
        if (note.isEmpty()) {
          throw bad("say why the repair is rejected: a note is required");
        }
      }
      case DISMISS_PAGE -> {
        if (note.isEmpty()) {
          throw bad("say what is wrong with the page: a note is required");
        }
      }
      case NEW_RECIPE -> {
        Path page =
            lab.archived(source, snapshot)
                .orElseThrow(
                    () ->
                        new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "this server does not have the archived page, so a new recipe cannot"
                                + " be checked here"));
        recipe = clean(req.recipe());
        RecipeVerifier.Verdict v =
            lab.verifier(source).verify(page, new SourceSpec.Recipe(0, recipe));
        if (!v.accepted()) {
          // The same rule as for the model: a recipe that does not read the page exactly is not
          // recorded, whoever wrote it.
          throw new ResponseStatusException(
              HttpStatus.UNPROCESSABLE_CONTENT, "the verifier rejected the recipe: " + v.error());
        }
        matched = v.matched();
        expected = v.expected();
      }
    }
    DecisionLog.Decision d =
        new DecisionLog.Decision(
            source, snapshot, req.action(), recipe, matched, expected, note, Instant.now());
    decisions.append(d);
    return d;
  }

  @GetMapping("/api/v1/lab")
  public LabInfo lab() throws IOException {
    Map<String, String> outcomes = new LinkedHashMap<>();
    cases
        .casebook(Lab.SOURCE)
        .ifPresent(b -> b.cases().forEach(c -> outcomes.put(c.snapshot(), c.outcome())));
    SourceSpec spec = sources.find(Lab.SOURCE).orElseThrow(() -> notFound("no spec"));
    return new LabInfo(
        Lab.SOURCE,
        spec.url(),
        true,
        key != null,
        lab.pages(),
        cases.knownRecipes(Lab.SOURCE),
        spec.recipeVersion(),
        outcomes,
        key == null ? List.of() : lab.models());
  }

  /**
   * Selectors only are applied here (no date pattern, no numbers parsed), so a typed recipe needs
   * no key: the worst it can do is outline the wrong elements.
   */
  @PostMapping("/api/v1/lab/view")
  public Lab.View view(@RequestBody ViewRequest req) throws IOException {
    if (req == null) {
      throw bad("empty request");
    }
    Path page = lab.file(req.pageId()).orElseThrow(() -> notFound("no such page"));
    SourceSpec spec = sources.find(Lab.SOURCE).orElseThrow(() -> notFound("no spec"));
    SourceSpec.Recipe outline = null;
    if (req.recipeVersion() != null) {
      outline =
          cases.knownRecipes(Lab.SOURCE).stream()
              .filter(k -> k.version() == req.recipeVersion())
              .findFirst()
              .orElseThrow(() -> notFound("no such recipe version"));
    } else if (req.recipe() != null) {
      outline = new SourceSpec.Recipe(0, clean(req.recipe()));
    }
    return lab.view(req.pageId(), page, outline, spec.recipe(), spec.url());
  }

  @PostMapping("/api/v1/lab/runs")
  public Lab.Result run(
      @RequestHeader(value = "X-Review-Key", required = false) String given,
      @RequestBody RunRequest req)
      throws IOException, InterruptedException {
    if (req == null) {
      throw bad("empty request");
    }
    int chosen =
        (req.recipeVersion() != null ? 1 : 0)
            + (req.model() != null ? 1 : 0)
            + (req.recipe() != null ? 1 : 0);
    if (chosen != 1) {
      throw bad("choose exactly one parser: recipeVersion, model or recipe");
    }
    Path page = lab.file(req.pageId()).orElseThrow(() -> notFound("no such page"));
    if (req.recipeVersion() != null) {
      SourceSpec.Recipe r =
          cases.knownRecipes(Lab.SOURCE).stream()
              .filter(k -> k.version() == req.recipeVersion())
              .findFirst()
              .orElseThrow(() -> notFound("no such recipe version"));
      return lab.runRecipe(req.pageId(), page, "recipe", r);
    }
    requireKey(given);
    if (req.model() != null) {
      SourceSpec.Recipe inUse =
          sources.find(Lab.SOURCE).map(SourceSpec::recipe).orElseThrow(() -> notFound("no spec"));
      return lab.runModel(req.pageId(), page, req.model(), inUse);
    }
    return lab.runRecipe(
        req.pageId(), page, "human", new SourceSpec.Recipe(0, clean(req.recipe())));
  }

  /**
   * One recipe over every page the lab has. A known recipe needs no key; a typed one does, as for
   * single runs.
   */
  @PostMapping("/api/v1/lab/batch")
  public Lab.Batch batch(
      @RequestHeader(value = "X-Review-Key", required = false) String given,
      @RequestBody RunRequest req)
      throws IOException {
    if (req == null || (req.recipeVersion() == null) == (req.recipe() == null)) {
      throw bad("choose exactly one recipe: recipeVersion or recipe");
    }
    if (req.recipeVersion() != null) {
      return lab.batch(
          cases.knownRecipes(Lab.SOURCE).stream()
              .filter(k -> k.version() == req.recipeVersion())
              .findFirst()
              .orElseThrow(() -> notFound("no such recipe version")));
    }
    requireKey(given);
    return lab.batch(new SourceSpec.Recipe(0, clean(req.recipe())));
  }

  @GetMapping("/api/v1/lab/bench")
  public tools.jackson.databind.JsonNode bench() throws IOException {
    try (java.io.InputStream in =
        getClass().getResourceAsStream("/bench/" + Lab.SOURCE + ".json")) {
      if (in == null) {
        throw notFound("no model benchmark has been run");
      }
      return JSON.readTree(in);
    }
  }

  private static final tools.jackson.databind.json.JsonMapper JSON =
      tools.jackson.databind.json.JsonMapper.builder().build();

  @ExceptionHandler(Lab.Busy.class)
  @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
  public Map<String, String> busy(Lab.Busy e) {
    return Map.of("error", e.getMessage());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public Map<String, String> badArgument(IllegalArgumentException e) {
    return Map.of("error", String.valueOf(e.getMessage()));
  }

  private Map<String, DecisionLog.Decision> latestDecisions() throws IOException {
    Map<String, DecisionLog.Decision> latest = new HashMap<>();
    for (DecisionLog.Decision d : decisions.all()) {
      latest.put(d.source() + "/" + d.snapshot(), d);
    }
    return latest;
  }

  private void requireKey(String given) {
    if (key == null) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "reviews are switched off on this server (no review key set)");
    }
    if (given == null || !MessageDigest.isEqual(key, given.getBytes(StandardCharsets.UTF_8))) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "wrong or missing review key");
    }
  }

  /** Only the recipe keys the extractor knows; blank values dropped. */
  private static Map<String, String> clean(Map<String, String> recipe) {
    if (recipe == null) {
      throw bad("a recipe is required");
    }
    Map<String, String> out = new LinkedHashMap<>();
    for (String k :
        List.of(
            "base",
            "quote",
            "amountSelector",
            "dateSelector",
            "dateFormat",
            "dateRegex",
            "rowSelector",
            "currencySelector",
            "rateSelector")) {
      String v = recipe.get(k);
      if (v != null && !v.isBlank()) {
        out.put(k, v.strip());
      }
    }
    if (!out.containsKey("quote")) {
      out.putIfAbsent("base", "EUR");
    }
    return out;
  }

  private static ResponseStatusException notFound(String why) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, why);
  }

  private static ResponseStatusException bad(String why) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, why);
  }
}
