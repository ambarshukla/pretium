package io.pretium.collector.review;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * What people decided about repair cases, as an append-only file of JSON lines. Nothing is ever
 * rewritten: a later decision about the same case supersedes an earlier one, and both stay on
 * record, so the history of who changed their mind, and why, is kept.
 */
@Component
public class DecisionLog {

  /** What a reviewer can decide about a case. */
  public enum Action {
    /** The machine's repair is right: confirmed by a person. */
    CONFIRM_REPAIR,
    /** The machine's repair passed the verifier but a person does not want it (say why). */
    REJECT_REPAIR,
    /** A person wrote a recipe; it is only recorded if the verifier accepts it on the page. */
    NEW_RECIPE,
    /** The page itself cannot be read correctly (a broken snapshot, say): nothing to repair. */
    DISMISS_PAGE
  }

  /**
   * One decision. For {@link Action#NEW_RECIPE}, {@code recipe} is what the person wrote and the
   * counts are the verifier's result on the page, which accepted it.
   */
  public record Decision(
      String source,
      String snapshot,
      Action action,
      Map<String, String> recipe,
      Integer matched,
      Integer expected,
      String note,
      Instant decidedAt) {}

  private final Path file;
  private final JsonMapper json;

  public DecisionLog(@Value("${pretium.review.dir}") Path dir, JsonMapper json) throws IOException {
    Files.createDirectories(dir);
    this.file = dir.resolve("decisions.jsonl");
    this.json = json;
  }

  public synchronized void append(Decision d) throws IOException {
    Files.writeString(
        file,
        json.writeValueAsString(d) + "\n",
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
  }

  /** Every decision, oldest first. */
  public synchronized List<Decision> all() throws IOException {
    if (!Files.exists(file)) {
      return List.of();
    }
    List<Decision> out = new ArrayList<>();
    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
      if (!line.isBlank()) {
        out.add(json.readValue(line, Decision.class));
      }
    }
    return out;
  }
}
