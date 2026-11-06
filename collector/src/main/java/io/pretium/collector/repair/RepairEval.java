package io.pretium.collector.repair;

import io.pretium.collector.source.SourceCatalog;
import io.pretium.collector.source.SourceSpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Measures selector repair against the real history of the ECB's rates page.
 *
 * <p>Input: archived copies of the page (one per month, 2017 to today, from the Internet Archive),
 * and the SDMX history, which says what every rate really was on every day. The page is replayed
 * backwards from today's recipe. Whenever the current recipe cannot read a version exactly, the
 * repairer is asked for a new one, which is accepted only if the verifier confirms it reads every
 * rate on that page exactly as the reference channel has it. Previously accepted recipes are tried
 * first, because real pages often switch back.
 *
 * <p>Output: one row per page and a summary, including the count that matters most: proposals that
 * read <em>wrong numbers</em>, which an unverified repair would have published.
 *
 * <p>Usage: {@code RepairEval <pages-dir> <sdmx-csv> <ollama-url> <model> <report.md>}
 */
public final class RepairEval {

  private static final int ATTEMPTS = 2;

  private RepairEval() {}

  enum Outcome {
    WORKED,
    REUSED,
    HEALED,
    HUMAN_NEEDED,
    NO_REFERENCE
  }

  record Row(
      String page,
      String date,
      Outcome outcome,
      int version,
      int llmCalls,
      long llmMillis,
      String note) {}

  public static void main(String[] args) throws Exception {
    Path pages = Path.of(args[0]);
    ReferenceTruth truth = ReferenceTruth.fromSdmxCsv(Path.of(args[1]));
    OllamaRepairer repairer = new OllamaRepairer(args[2], args[3]);
    Path report = Path.of(args[4]);

    SourceSpec spec =
        new SourceCatalog()
            .find("ecb-reference-html")
            .orElseThrow(() -> new IllegalStateException("no spec"));
    RecipeVerifier verifier = new RecipeVerifier(truth);

    List<Path> files;
    try (Stream<Path> s = Files.list(pages)) {
      files =
          s.filter(p -> p.toString().endsWith(".html")).sorted(Comparator.reverseOrder()).toList();
    }

    SourceSpec.Recipe current = spec.recipe();
    List<SourceSpec.Recipe> library = new ArrayList<>(List.of(current));
    int nextVersion = current.version() + 1;
    List<Row> rows = new ArrayList<>();
    List<Casebook.Case> cases = new ArrayList<>();
    int proposals = 0;
    int proposalsAccepted = 0;
    int proposalsWithWrongNumbers = 0;
    int wrongNumbersBlocked = 0;
    long llmMillisTotal = 0;
    int promptTokens = 0;
    int outputTokens = 0;

    for (Path page : files) {
      String name = page.getFileName().toString();
      String snapshot = name.replace(".html", "");
      String archiveUrl = "https://web.archive.org/web/" + snapshot + "/" + spec.url();
      SourceSpec.Recipe failing = current;
      RecipeVerifier.Verdict verdict = verifier.verify(page, current);
      if (verdict.accepted()) {
        rows.add(
            new Row(
                name, String.valueOf(verdict.date()), Outcome.WORKED, current.version(), 0, 0, ""));
        continue;
      }
      if (verdict.date() != null && verdict.expected() == 0) {
        rows.add(
            new Row(
                name,
                String.valueOf(verdict.date()),
                Outcome.NO_REFERENCE,
                current.version(),
                0,
                0,
                verdict.error()));
        continue;
      }

      SourceSpec.Recipe known = null;
      RecipeVerifier.Verdict knownVerdict = null;
      for (SourceSpec.Recipe candidate : library) {
        RecipeVerifier.Verdict v = verifier.verify(page, candidate);
        if (v.accepted()) {
          known = candidate;
          knownVerdict = v;
          break;
        }
      }
      if (known != null) {
        current = known;
        rows.add(
            new Row(
                name,
                String.valueOf(knownVerdict.date()),
                Outcome.REUSED,
                known.version(),
                0,
                0,
                ""));
        cases.add(
            new Casebook.Case(
                snapshot,
                archiveUrl,
                String.valueOf(knownVerdict.date()),
                Outcome.REUSED.name(),
                Casebook.Attempt.of("recipe", failing, verdict),
                List.of(),
                Casebook.Attempt.of("library", known, knownVerdict)));
        continue;
      }

      String digest = PageDigest.of(page);
      PageDigest.DateHint dateHint = PageDigest.dateHint(page);
      String error = verdict.error();
      SourceSpec.Recipe base = current;
      boolean healed = false;
      int calls = 0;
      long millis = 0;
      StringBuilder note = new StringBuilder("v" + current.version() + ": " + error);
      List<Casebook.Attempt> tried = new ArrayList<>();
      // The failing recipe may not have found the date; a proposal that did tells us the day.
      LocalDate seenDate = verdict.date();
      for (int attempt = 1; attempt <= ATTEMPTS && !healed; attempt++) {
        OllamaRepairer.Proposal proposal;
        try {
          proposal = repairer.propose(base, error, digest, dateHint);
        } catch (IOException e) {
          note.append(" | model error: ").append(e.getMessage());
          break;
        }
        calls++;
        proposals++;
        millis += proposal.millis();
        promptTokens += proposal.promptTokens();
        outputTokens += proposal.outputTokens();
        SourceSpec.Recipe candidate =
            new SourceSpec.Recipe(nextVersion, proposal.recipe().settings());
        RecipeVerifier.Verdict v = verifier.verify(page, candidate);
        tried.add(Casebook.Attempt.proposed(proposal, candidate, v));
        if (seenDate == null) {
          seenDate = v.date();
        }
        if (v.wrongCount() > 0) {
          proposalsWithWrongNumbers++;
          wrongNumbersBlocked += v.wrongCount();
        }
        if (v.accepted()) {
          proposalsAccepted++;
          healed = true;
          current = candidate;
          library.add(candidate);
          nextVersion++;
          rows.add(
              new Row(
                  name,
                  String.valueOf(v.date()),
                  Outcome.HEALED,
                  candidate.version(),
                  calls,
                  millis,
                  "rows="
                      + candidate.get("rowSelector", "")
                      + " rate="
                      + candidate.get("rateSelector", "")));
        } else {
          note.append(" | attempt ").append(attempt).append(": ").append(v.error());
          // The second attempt is told why the first was rejected.
          error = "a previous proposal " + candidate.settings() + " was rejected: " + v.error();
          base = candidate;
        }
      }
      llmMillisTotal += millis;
      cases.add(
          new Casebook.Case(
              snapshot,
              archiveUrl,
              String.valueOf(seenDate),
              healed ? Outcome.HEALED.name() : Outcome.HUMAN_NEEDED.name(),
              Casebook.Attempt.of("recipe", failing, verdict),
              List.copyOf(tried),
              healed ? tried.get(tried.size() - 1) : null));
      if (!healed) {
        rows.add(
            new Row(
                name,
                String.valueOf(seenDate),
                Outcome.HUMAN_NEEDED,
                current.version(),
                calls,
                millis,
                note.toString()));
      }
      System.out.printf("%s %s%n", name, rows.get(rows.size() - 1).outcome());
    }

    write(
        report,
        rows,
        repairer.model(),
        library,
        proposals,
        proposalsAccepted,
        proposalsWithWrongNumbers,
        wrongNumbersBlocked,
        llmMillisTotal,
        promptTokens,
        outputTokens);
    String reportName = report.getFileName().toString();
    Path casebook =
        report.resolveSibling(
            (reportName.endsWith(".md")
                    ? reportName.substring(0, reportName.length() - 3)
                    : reportName)
                + ".json");
    JsonMapper.builder()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .build()
        .writeValue(
            casebook.toFile(),
            new Casebook(
                spec.id(),
                spec.url(),
                repairer.model(),
                Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
                cases));
    System.out.println("report: " + report.toAbsolutePath());
    System.out.println("casebook: " + casebook.toAbsolutePath());
  }

  private static void write(
      Path report,
      List<Row> rows,
      String model,
      List<SourceSpec.Recipe> library,
      int proposals,
      int accepted,
      int withWrong,
      int wrongBlocked,
      long millis,
      int promptTokens,
      int outputTokens)
      throws IOException {
    StringBuilder md = new StringBuilder();
    md.append("# Selector repair against the real history of the ECB rates page\n\n");
    md.append("Model: `")
        .append(model)
        .append("` (local, Ollama). Pages: ")
        .append(rows.size())
        .append(".\n\n");
    md.append("| Outcome | Pages |\n|---|---|\n");
    for (Outcome o : Outcome.values()) {
      long n = rows.stream().filter(r -> r.outcome() == o).count();
      md.append("| ").append(o).append(" | ").append(n).append(" |\n");
    }
    md.append("\n| Repair metric | Value |\n|---|---|\n");
    md.append("| Model proposals | ").append(proposals).append(" |\n");
    md.append("| Accepted by the verifier | ").append(accepted).append(" |\n");
    md.append("| Rejected proposals that read wrong numbers | ").append(withWrong).append(" |\n");
    md.append("| Wrong numbers blocked from publication | ").append(wrongBlocked).append(" |\n");
    md.append(
        "| False repairs accepted | 0 (by construction: acceptance requires an exact match) |\n");
    md.append(String.format(Locale.ROOT, "| Model time, total | %.1f s |%n", millis / 1000.0));
    md.append(
        String.format(
            Locale.ROOT,
            "| Model time per proposal | %.1f s |%n",
            proposals == 0 ? 0 : millis / 1000.0 / proposals));
    md.append("| Tokens in / out | ")
        .append(promptTokens)
        .append(" / ")
        .append(outputTokens)
        .append(" |\n");
    md.append("\n## Recipes in use over time\n\n");
    for (SourceSpec.Recipe r : library) {
      md.append("- v").append(r.version()).append(": `").append(r.settings()).append("`\n");
    }
    md.append(
        "\n## Page by page (newest first)\n\n| Snapshot | Rates date | Outcome | Recipe | Model calls | Note |\n|---|---|---|---|---|---|\n");
    for (Row r : rows) {
      md.append("| ")
          .append(r.page().replace(".html", ""))
          .append(" | ")
          .append(r.date())
          .append(" | ")
          .append(r.outcome())
          .append(" | v")
          .append(r.version())
          .append(" | ")
          .append(r.llmCalls())
          .append(" | ")
          .append(r.note().replace("|", "\\|"))
          .append(" |\n");
    }
    Files.writeString(report, md.toString(), StandardCharsets.UTF_8);
  }
}
