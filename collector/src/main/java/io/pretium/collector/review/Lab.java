package io.pretium.collector.review;

import io.pretium.collector.extract.ExtractionException;
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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;
import java.util.stream.Stream;
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
      String digest) {

    static Result of(
        String pageId, String by, String model, SourceSpec.Recipe r, RecipeVerifier.Verdict v) {
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
          null);
    }
  }

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
    return Result.of(pageId, by, null, recipe, verifier().verify(page, recipe));
  }

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
      Result r = Result.of(pageId, "model", model, p.recipe(), v);
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
          digest);
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
