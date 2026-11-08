package io.pretium.collector.repair;

import io.pretium.collector.source.SourceCatalog;
import io.pretium.collector.source.SourceSpec;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Two ways to use a model on a page whose layout changed, measured side by side on the same pages,
 * models and page digests:
 *
 * <ol>
 *   <li><b>Repair</b>: the model proposes CSS selectors; code reads the numbers with them; the
 *       verifier compares every number with the ECB's structured channel and accepts only an exact
 *       match. A wrong proposal is visible and blocked.
 *   <li><b>Extract</b>: the model reads the rates itself and types them out. Nothing reads the page
 *       but the model, so every number it gets wrong looks exactly like one it got right.
 * </ol>
 *
 * <p>The question it answers: how many wrong numbers would each approach have published? For the
 * repair path the answer is zero by construction; the bench shows what it costs (time, tokens,
 * pages a model could not repair). For the extract path, it counts them.
 *
 * <p>Every number is scored against the SDMX history for the page's true date, which comes from a
 * recipe the verifier accepted on that page, not from the model.
 *
 * <p>Usage: {@code ModelBench <pages-dir> <sdmx-csv> <ollama-url> <models,comma,separated>
 * <every-nth> <out.json>}. Results are written after every model call, so a run that is stopped
 * keeps what it has.
 */
public final class ModelBench {

  private static final String EXTRACT_PROMPT =
      """
      Below is part of a web page that publishes the euro foreign exchange reference rates for one
      day: how many units of each currency one euro buys.

      %s

      Read the date of the rates and every currency and rate in the table.
      Reply with JSON only, in exactly this shape:
        {"date": "YYYY-MM-DD", "rates": {"USD": "1.2345", "JPY": "123.45"}}
      with one entry per currency in the table: the three-letter code, and the rate exactly as it
      is written on the page, as a string.
      """;

  private static final Pattern CODE = Pattern.compile("[A-Z]{3}");

  private ModelBench() {}

  /** One page and what each approach made of it. */
  public record PageResult(
      String snapshot,
      String ratesDate,
      int staleVersion,
      int digestChars,
      int visible,
      Repair repair,
      Extract extract) {}

  /**
   * The repair approach on one page, first proposal only.
   *
   * @param wrong numbers the proposal read that differ from the reference; blocked by the verifier
   */
  public record Repair(
      boolean accepted,
      int matched,
      int expected,
      int wrong,
      String error,
      Map<String, String> recipe,
      long millis,
      int promptTokens,
      int outputTokens,
      String reply) {}

  /**
   * The extract approach on one page.
   *
   * @param dateVisible whether the page's true date, as the page writes it, is in the digest
   * @param visible reference currencies whose code appears in the digest the model was shown
   * @param exact rates typed exactly as the reference has them
   * @param wrong rates typed for a real currency, but not the reference value
   * @param invented currencies the model returned that the reference does not have that day, or
   *     values that are not numbers
   * @param missing visible currencies the model did not return
   * @param mistakes each wrong or invented value, as "USD 1.1234 (reference 1.1243)"
   */
  public record Extract(
      boolean dateVisible,
      boolean dateRight,
      String dateRead,
      int exact,
      int wrong,
      int invented,
      int missing,
      List<String> mistakes,
      long millis,
      int promptTokens,
      int outputTokens,
      String reply) {}

  /** All results for one model. */
  public record ModelResult(String model, List<PageResult> pages) {}

  /** The file the web page shows. */
  public record Bench(
      String source,
      String generatedAt,
      String method,
      List<String> snapshots,
      List<ModelResult> models) {}

  public static void main(String[] args) throws Exception {
    Path pagesDir = Path.of(args[0]);
    String source = args.length > 8 && !args[8].isBlank() ? args[8] : "ecb-reference-html";
    ReferenceTruth truth = ReferenceTruth.forPage(source, args[1]);
    String ollama = args[2];
    List<String> models = List.of(args[3].split(","));
    int every = Integer.parseInt(args[4]);
    Path out = Path.of(args[5]);

    JsonMapper json = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    SourceSpec spec = new SourceCatalog().find(source).orElseThrow();
    List<SourceSpec.Recipe> known = knownRecipes(spec, json);
    // "table": only the direct reading, shown the whole table instead of the digest.
    boolean wholeTable = args.length > 6 && args[6].equals("table");
    // Optionally, only these snapshots (comma separated), e.g. to re-run pages after a fix.
    java.util.Set<String> only =
        args.length > 7 && !args[7].isBlank()
            ? java.util.Set.of(args[7].split(","))
            : java.util.Set.of();
    RecipeVerifier verifier = new RecipeVerifier(truth);

    List<Path> all;
    try (Stream<Path> s = Files.list(pagesDir)) {
      all = s.filter(p -> p.toString().endsWith(".html")).sorted().toList();
    }
    List<Path> pages = new ArrayList<>();
    for (int i = 0; i < all.size(); i += every) {
      if (only.isEmpty()
          || only.contains(all.get(i).getFileName().toString().replace(".html", ""))) {
        pages.add(all.get(i));
      }
    }
    List<String> snapshots =
        pages.stream().map(p -> p.getFileName().toString().replace(".html", "")).toList();

    List<ModelResult> results = new ArrayList<>();
    for (String model : models) {
      OllamaRepairer repairer = new OllamaRepairer(ollama, model);
      List<PageResult> pageResults = new ArrayList<>();
      results.add(new ModelResult(model, pageResults));
      for (Path page : pages) {
        pageResults.add(run(page, known, verifier, truth, repairer, json, wholeTable));
        PageResult r = pageResults.get(pageResults.size() - 1);
        System.out.printf(
            "%s %s repair=%s extract exact=%d wrong=%d invented=%d missing=%d%n",
            model,
            r.snapshot(),
            r.repair() == null ? "-" : r.repair().accepted(),
            r.extract().exact(),
            r.extract().wrong(),
            r.extract().invented(),
            r.extract().missing());
        json.writeValue(
            out.toFile(),
            new Bench(
                spec.id(),
                Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
                wholeTable
                    ? "every "
                        + every
                        + "th archived page; direct reading only, shown the whole"
                        + " table; temperature 0"
                    : "every "
                        + every
                        + "th archived page; one repair proposal and one extraction"
                        + " per page and model; temperature 0; same digest for both",
                snapshots,
                results));
      }
    }
    System.out.println("bench: " + out.toAbsolutePath());
  }

  static PageResult run(
      Path page,
      List<SourceSpec.Recipe> known,
      RecipeVerifier verifier,
      ReferenceTruth truth,
      OllamaRepairer repairer,
      JsonMapper json,
      boolean wholeTable)
      throws IOException, InterruptedException {
    String snapshot = page.getFileName().toString().replace(".html", "");

    // The truth about this page: its date, from a recipe the verifier accepted (or at least one
    // that found the date). And a stale recipe to repair: the first known one that fails here.
    LocalDate acceptedDate = null;
    LocalDate anyDate = null;
    SourceSpec.Recipe stale = null;
    RecipeVerifier.Verdict staleVerdict = null;
    for (SourceSpec.Recipe r : known) {
      RecipeVerifier.Verdict v = verifier.verify(page, r);
      if (v.accepted() && acceptedDate == null) {
        acceptedDate = v.date();
      }
      if (v.date() != null && anyDate == null) {
        anyDate = v.date();
      }
      if (!v.accepted() && stale == null) {
        stale = r;
        staleVerdict = v;
      }
    }
    if (stale == null) {
      stale = known.get(0);
      staleVerdict = verifier.verify(page, stale);
    }
    LocalDate date = acceptedDate != null ? acceptedDate : anyDate;
    Map<String, BigDecimal> expected = date == null ? Map.of() : truth.on(date);
    String table = wholeTable ? PageDigest.table(page) : null;
    String digest = table != null ? table : PageDigest.of(page);
    int visible =
        (int)
            expected.keySet().stream()
                .filter(c -> Pattern.compile("\\b" + c + "\\b").matcher(digest).find())
                .count();

    // 1. Repair: one proposal, verified.
    Repair repair = null;
    if (!wholeTable) {
      OllamaRepairer.Proposal p =
          repairer.propose(
              stale,
              staleVerdict.error() == null
                  ? "the recipe did not read the page"
                  : staleVerdict.error(),
              digest,
              PageDigest.dateHint(page));
      RecipeVerifier.Verdict v = verifier.verify(page, p.recipe());
      repair =
          new Repair(
              v.accepted(),
              v.matched(),
              v.expected(),
              v.wrongCount(),
              v.error(),
              p.recipe().settings(),
              p.millis(),
              p.promptTokens(),
              p.outputTokens(),
              p.raw());
    }

    // 2. Extract: the model types the numbers.
    OllamaRepairer.Reply reply = repairer.generate(EXTRACT_PROMPT.formatted(digest));
    Extract extract = score(reply, date, expected, digest, json);

    return new PageResult(
        snapshot, String.valueOf(date), stale.version(), digest.length(), visible, repair, extract);
  }

  static Extract score(
      OllamaRepairer.Reply reply,
      LocalDate date,
      Map<String, BigDecimal> expected,
      String digest,
      JsonMapper json) {
    JsonNode node;
    try {
      node = json.readTree(reply.text().isBlank() ? "{}" : reply.text());
    } catch (RuntimeException e) {
      node = json.readTree("{}");
    }
    String dateRead = node.path("date").asString("");
    int exact = 0;
    int wrong = 0;
    int invented = 0;
    List<String> mistakes = new ArrayList<>();
    java.util.Set<String> returned = new java.util.HashSet<>();
    JsonNode rates = node.path("rates");
    for (Map.Entry<String, JsonNode> e : rates.properties()) {
      String code = e.getKey().strip().toUpperCase(java.util.Locale.ROOT);
      String text = e.getValue().isString() ? e.getValue().asString() : e.getValue().toString();
      returned.add(code);
      BigDecimal value;
      try {
        value = new BigDecimal(text.strip().replace(",", ""));
      } catch (NumberFormatException ex) {
        value = null;
      }
      BigDecimal ref = expected.get(code);
      if (!CODE.matcher(code).matches() || ref == null || value == null) {
        invented++;
        mistakes.add(code + " " + text + " (reference: none)");
      } else if (ref.compareTo(value) == 0) {
        exact++;
      } else {
        wrong++;
        mistakes.add(code + " " + text + " (reference " + ref.toPlainString() + ")");
      }
    }
    int missing = 0;
    for (String c : expected.keySet()) {
      if (!returned.contains(c) && Pattern.compile("\\b" + c + "\\b").matcher(digest).find()) {
        missing++;
      }
    }
    boolean dateVisible =
        date != null
            && digest.contains(
                date.format(
                    java.time.format.DateTimeFormatter.ofPattern(
                        "d MMMM yyyy", java.util.Locale.ENGLISH)));
    return new Extract(
        dateVisible,
        date != null && date.toString().equals(dateRead),
        dateRead,
        exact,
        wrong,
        invented,
        missing,
        List.copyOf(mistakes),
        reply.millis(),
        reply.promptTokens(),
        reply.outputTokens(),
        reply.text());
  }

  /** The recipe in use, then every recipe the shipped casebook shows the verifier accepted. */
  static List<SourceSpec.Recipe> knownRecipes(SourceSpec spec, JsonMapper json) throws IOException {
    Map<Integer, SourceSpec.Recipe> byVersion = new LinkedHashMap<>();
    byVersion.put(spec.recipe().version(), spec.recipe());
    try (InputStream in = ModelBench.class.getResourceAsStream("/repair/" + spec.id() + ".json")) {
      if (in != null) {
        Casebook book = json.readValue(in, Casebook.class);
        for (Casebook.Case c : book.cases()) {
          if (c.after() != null && c.after().accepted()) {
            byVersion.putIfAbsent(
                c.after().recipeVersion(),
                new SourceSpec.Recipe(c.after().recipeVersion(), c.after().recipe()));
          }
        }
      }
    }
    return byVersion.values().stream()
        .sorted(Comparator.comparingInt(SourceSpec.Recipe::version))
        .toList();
  }
}
