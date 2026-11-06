package io.pretium.collector.review;

import io.pretium.collector.extract.ExtractionException;
import io.pretium.collector.extract.HtmlRateTableExtractor;
import io.pretium.collector.raw.RawStore;
import io.pretium.collector.repair.OllamaRepairer;
import io.pretium.collector.repair.PageDigest;
import io.pretium.collector.repair.RecipeVerifier;
import io.pretium.collector.repair.ReferenceTruth;
import io.pretium.collector.source.SourceSpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Range;
import org.jsoup.parser.Parser;
import org.jsoup.select.Selector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs a parser against a raw page and shows what it reads, next to what the ECB's structured
 * channel says, without storing anything or sending anything to Kafka. The parser is either a known
 * recipe, one a person wrote, or whatever a model proposes for that page.
 *
 * <p>Pages come from two places: what the collector itself fetched (the evidence store), and, where
 * configured, a folder of archived copies of the page from the Internet Archive.
 */
@Component
public class Lab {

  static final String SOURCE = "ecb-reference-html";
  static final String TRUTH_SOURCE = "ecb-sdmx-exr";

  private static final Pattern SNAPSHOT = Pattern.compile("\\d{14}");
  private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");
  private static final DateTimeFormatter SNAPSHOT_TIME =
      DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

  /** A page the lab can run a parser against. {@code id} is "archive:<ts>" or "collected:<sha>". */
  public record Page(String id, String origin, String when) {}

  /** What one parser read from one page. Nothing here is stored. */
  public record Result(
      String pageId,
      String by,
      String model,
      Map<String, String> recipe,
      boolean accepted,
      String error,
      String ratesDate,
      int read,
      int expected,
      int matched,
      List<RecipeVerifier.Reading> readings,
      Long millis,
      Integer promptTokens,
      Integer outputTokens,
      String modelReply,
      String promptError,
      String digest,
      String prompt,
      HtmlRateTableExtractor.Trace trace) {

    static Result of(
        String pageId,
        String by,
        String model,
        SourceSpec.Recipe r,
        RecipeVerifier.Verdict v,
        HtmlRateTableExtractor.Trace trace) {
      return new Result(
          pageId,
          by,
          model,
          r.settings(),
          v.accepted(),
          v.error(),
          v.date() == null ? null : v.date().toString(),
          v.read(),
          v.expected(),
          v.matched(),
          v.readings(),
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          trace);
    }
  }

  /**
   * Where a recipe's selectors landed in the page's source: {@code kind} is date, row, currency or
   * rate; {@code row} is the row's index for the last three; {@code line} is 1-based.
   */
  public record Mark(String kind, int line, Integer row) {}

  /**
   * One raw page, for reading in the browser.
   *
   * @param source the file exactly as stored
   * @param rendered the page with scripts removed, nothing loaded from elsewhere, and what the
   *     recipe matched outlined; meant for a sandboxed frame
   * @param region the same, cut down to the smallest element holding everything the recipe matched
   *     (the date and the table), or null if it matched nothing
   * @param marks where each selector matched, by source line
   * @param selectorError why a selector could not be applied, if one was not valid CSS
   * @param digest the part of the page a model is shown
   * @param prompt the prompt a model would be sent for this page, given the recipe in use
   */
  public record View(
      String pageId,
      String archiveUrl,
      int bytes,
      String source,
      String rendered,
      String region,
      List<Mark> marks,
      String selectorError,
      String digest,
      String prompt) {}

  /** Thrown when a model is already working: one at a time, since each takes about a minute. */
  public static class Busy extends RuntimeException {
    Busy() {
      super("a model run is already in progress; try again when it has finished");
    }
  }

  private final RawStore raw;
  private final Path rawDir;
  private final Path archiveDir;
  private final String ollamaUrl;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private final JsonMapper json;
  private final Semaphore modelSlot = new Semaphore(1);

  private ReferenceTruth truth;
  private List<String> truthFiles = List.of();

  public Lab(
      RawStore raw,
      JsonMapper json,
      @Value("${pretium.raw-dir}") Path rawDir,
      @Value("${pretium.lab.archive-dir:}") String archiveDir,
      @Value("${pretium.lab.ollama-url:}") String ollamaUrl) {
    this.raw = raw;
    this.json = json;
    this.rawDir = rawDir;
    this.archiveDir = archiveDir.isBlank() ? null : Path.of(archiveDir);
    this.ollamaUrl = ollamaUrl.isBlank() ? null : ollamaUrl.replaceAll("/+$", "");
  }

  /** Pages to choose from, newest first: collected ones, then archived ones. */
  public List<Page> pages() throws IOException {
    List<Page> out = new ArrayList<>();
    Map<String, RawStore.Entry> latestBySha = new LinkedHashMap<>();
    for (RawStore.Entry e : raw.entries(SOURCE)) {
      latestBySha.put(e.sha256(), e);
    }
    latestBySha.values().stream()
        .sorted(Comparator.comparing(RawStore.Entry::fetchedAt).reversed())
        .forEach(
            e ->
                out.add(
                    new Page("collected:" + e.sha256(), "collected", e.fetchedAt().toString())));
    if (archiveDir != null && Files.isDirectory(archiveDir)) {
      try (Stream<Path> files = Files.list(archiveDir)) {
        files
            .map(p -> p.getFileName().toString())
            .filter(n -> n.endsWith(".html"))
            .map(n -> n.substring(0, n.length() - 5))
            .filter(n -> SNAPSHOT.matcher(n).matches())
            .sorted(Comparator.reverseOrder())
            .forEach(
                ts ->
                    out.add(
                        new Page(
                            "archive:" + ts,
                            "archive",
                            LocalDateTime.parse(ts, SNAPSHOT_TIME)
                                .toInstant(ZoneOffset.UTC)
                                .toString())));
      }
    }
    return out;
  }

  /**
   * The file behind a page id. Ids are checked against strict patterns, so a request cannot name a
   * file outside the two folders.
   */
  public Optional<Path> file(String pageId) throws IOException {
    if (pageId == null) {
      return Optional.empty();
    }
    if (pageId.startsWith("archive:") && archiveDir != null) {
      String ts = pageId.substring("archive:".length());
      if (SNAPSHOT.matcher(ts).matches()) {
        Path p = archiveDir.resolve(ts + ".html");
        return Files.isRegularFile(p) ? Optional.of(p) : Optional.empty();
      }
    }
    if (pageId.startsWith("collected:")) {
      String sha = pageId.substring("collected:".length());
      if (SHA.matcher(sha).matches()) {
        for (RawStore.Entry e : raw.entries(SOURCE)) {
          if (e.sha256().equals(sha)) {
            Path p = raw.pathOf(SOURCE, e);
            return Files.isRegularFile(p) ? Optional.of(p) : Optional.empty();
          }
        }
      }
    }
    return Optional.empty();
  }

  /** The archived copy of a repair case's page, if this server has the archive. */
  public Optional<Path> archived(String snapshot) throws IOException {
    return file("archive:" + snapshot);
  }

  /** Models the local model runner has, or none if no runner is configured or reachable. */
  public List<String> models() {
    if (ollamaUrl == null) {
      return List.of();
    }
    try {
      HttpResponse<String> r =
          http.send(
              HttpRequest.newBuilder(URI.create(ollamaUrl + "/api/tags"))
                  .timeout(Duration.ofSeconds(3))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (r.statusCode() != 200) {
        return List.of();
      }
      List<String> names = new ArrayList<>();
      for (JsonNode m : json.readTree(r.body()).path("models")) {
        names.add(m.path("name").asString(""));
      }
      return names.stream().filter(n -> !n.isBlank()).sorted().toList();
    } catch (IOException e) {
      return List.of();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return List.of();
    }
  }

  public Result runRecipe(String pageId, Path page, String by, SourceSpec.Recipe recipe)
      throws IOException {
    return Result.of(
        pageId, by, null, recipe, verifier().verify(page, recipe), trace(page, recipe));
  }

  static HtmlRateTableExtractor.Trace trace(Path page, SourceSpec.Recipe recipe)
      throws IOException {
    return HtmlRateTableExtractor.trace(
        Jsoup.parse(page.toFile(), StandardCharsets.UTF_8.name()), recipe);
  }

  /** The archived copy's address at the Internet Archive, for archive pages. */
  static String archiveUrl(String pageId, String pageUrl) {
    return pageId.startsWith("archive:")
        ? "https://web.archive.org/web/" + pageId.substring("archive:".length()) + "/" + pageUrl
        : null;
  }

  /**
   * The page for reading: its source, a safe rendering with the recipe's matches outlined, where
   * each selector matched, and what a model would be shown and asked.
   */
  public View view(
      String pageId, Path page, SourceSpec.Recipe recipe, SourceSpec.Recipe inUse, String pageUrl)
      throws IOException {
    String source = Files.readString(page, StandardCharsets.UTF_8);
    Document doc = Jsoup.parse(source, "", Parser.htmlParser().setTrackPosition(true));
    List<Mark> marks = new ArrayList<>();
    List<Element> marked = new ArrayList<>();
    String selectorError = null;
    if (recipe != null) {
      try {
        mark(doc, recipe, marks, marked);
      } catch (Selector.SelectorParseException e) {
        selectorError = e.getMessage();
      }
    }
    String digest = PageDigest.of(page);
    RecipeVerifier.Verdict current = verifier().verify(page, inUse);
    String error =
        current.accepted()
            ? "none: the recipe in use reads this page; propose a recipe for it anyway"
            : current.error();
    return new View(
        pageId,
        archiveUrl(pageId, pageUrl),
        source.getBytes(StandardCharsets.UTF_8).length,
        source,
        safe(doc),
        region(doc, marked),
        List.copyOf(marks),
        selectorError,
        digest,
        OllamaRepairer.prompt(inUse, error, digest));
  }

  private static void mark(
      Document doc, SourceSpec.Recipe recipe, List<Mark> marks, List<Element> marked) {
    String dateSelector = recipe.get("dateSelector", null);
    if (dateSelector != null) {
      Element date = doc.selectFirst(dateSelector);
      if (date != null) {
        marks.add(new Mark("date", line(date), null));
        marked.add(date);
        date.addClass("pretium-date");
      }
    }
    String rowSelector = recipe.get("rowSelector", null);
    if (rowSelector == null) {
      return;
    }
    String currencySelector = recipe.get("currencySelector", null);
    String rateSelector = recipe.get("rateSelector", null);
    int i = 0;
    for (Element row : doc.select(rowSelector)) {
      marks.add(new Mark("row", line(row), i));
      marked.add(row);
      Element currency = currencySelector == null ? null : row.selectFirst(currencySelector);
      if (currency != null) {
        marks.add(new Mark("currency", line(currency), i));
        currency.addClass("pretium-currency");
      }
      Element rate = rateSelector == null ? null : row.selectFirst(rateSelector);
      if (rate != null) {
        marks.add(new Mark("rate", line(rate), i));
        rate.addClass("pretium-rate");
      }
      row.addClass("pretium-row");
      i++;
    }
  }

  /**
   * The smallest element holding everything marked, as a page of its own with the same safety
   * header. Call after {@link #safe}, which has already cleaned the document.
   */
  private static String region(Document doc, List<Element> marked) {
    if (marked.isEmpty()) {
      return null;
    }
    Element common = marked.get(0);
    for (Element e : marked) {
      while (common != null && common != e && !e.parents().contains(common)) {
        common = common.parent();
      }
    }
    if (common == null || common == doc.body() || common == doc) {
      return null;
    }
    return "<!doctype html><html><head>"
        + doc.head().html()
        + "</head><body>"
        + common.outerHtml()
        + "</body></html>";
  }

  private static int line(Element e) {
    Range r = e.sourceRange();
    return r.isTracked() ? r.start().lineNumber() : 0;
  }

  /**
   * The page as something safe to show in a sandboxed frame: no scripts, no frames, no event
   * handlers, and a content security policy that stops it loading anything from anywhere, so
   * opening it sends no request to the ECB or the archive.
   */
  private static String safe(Document doc) {
    doc.select("script, noscript, iframe, frame, object, embed, base, link, meta[http-equiv]")
        .remove();
    for (Element e : doc.getAllElements()) {
      List<String> handlers =
          e.attributes().asList().stream()
              .map(Attribute::getKey)
              .filter(k -> k.toLowerCase(Locale.ROOT).startsWith("on"))
              .toList();
      handlers.forEach(e::removeAttr);
    }
    doc.head()
        .prepend(
            "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none';"
                + " style-src 'unsafe-inline'; img-src data:\">")
        .appendElement("style")
        .appendText(HIGHLIGHT);
    return doc.outerHtml();
  }

  private static final String HIGHLIGHT =
      """
      body { font-family: system-ui, sans-serif; font-size: 14px; margin: 12px; }
      .pretium-row { outline: 2px solid #2563eb; outline-offset: -1px; background: #eff6ff; }
      .pretium-currency { outline: 2px solid #16a34a; background: #dcfce7; }
      .pretium-rate { outline: 2px solid #ea580c; background: #ffedd5; }
      .pretium-date { outline: 2px solid #9333ea; background: #f3e8ff; }
      """;

  /**
   * Asks a model for a recipe for this page, exactly as the repair loop would (same prompt, same
   * page digest, the date found by code), then verifies what it proposed.
   */
  public Result runModel(String pageId, Path page, String model, SourceSpec.Recipe inUse)
      throws IOException, InterruptedException {
    if (ollamaUrl == null || !models().contains(model)) {
      throw new IllegalArgumentException("model not available on this server: " + model);
    }
    if (!modelSlot.tryAcquire()) {
      throw new Busy();
    }
    try {
      RecipeVerifier verifier = verifier();
      RecipeVerifier.Verdict current = verifier.verify(page, inUse);
      String error =
          current.accepted()
              ? "none: the recipe in use reads this page; propose a recipe for it anyway"
              : current.error();
      String digest = PageDigest.of(page);
      OllamaRepairer.Proposal p =
          new OllamaRepairer(ollamaUrl, model)
              .propose(inUse, error, digest, PageDigest.dateHint(page));
      RecipeVerifier.Verdict v = verifier.verify(page, p.recipe());
      Result r = Result.of(pageId, "model", model, p.recipe(), v, trace(page, p.recipe()));
      return new Result(
          r.pageId(),
          r.by(),
          r.model(),
          r.recipe(),
          r.accepted(),
          r.error(),
          r.ratesDate(),
          r.read(),
          r.expected(),
          r.matched(),
          r.readings(),
          p.millis(),
          p.promptTokens(),
          p.outputTokens(),
          p.raw(),
          error,
          digest,
          p.prompt(),
          r.trace());
    } finally {
      modelSlot.release();
    }
  }

  /** The verifier over every SDMX file collected so far, reloaded when a new one arrives. */
  public synchronized RecipeVerifier verifier() throws IOException {
    List<Path> files;
    Path dir = rawDir.resolve(TRUTH_SOURCE);
    if (!Files.isDirectory(dir)) {
      files = List.of();
    } else {
      try (Stream<Path> s = Files.list(dir)) {
        files = s.filter(p -> p.toString().endsWith(".csv")).sorted().toList();
      }
    }
    List<String> names = files.stream().map(p -> p.getFileName().toString()).toList();
    if (truth == null || !names.equals(truthFiles)) {
      try {
        truth = ReferenceTruth.fromSdmxCsv(files);
      } catch (ExtractionException e) {
        throw new IOException("cannot load the reference rates: " + e.getMessage(), e);
      }
      truthFiles = names;
    }
    return new RecipeVerifier(truth);
  }
}
