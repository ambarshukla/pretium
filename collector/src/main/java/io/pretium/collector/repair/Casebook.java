package io.pretium.collector.repair;

import io.pretium.collector.source.SourceSpec;
import java.util.List;
import java.util.Map;

/**
 * The record of every page the current recipe could not read, and what happened next: which recipe
 * failed and how, what the model proposed (with its raw reply, time and tokens), what the verifier
 * said about each proposal, and which recipe finally read the page, if any.
 *
 * <p>It is written by {@link RepairEval} as JSON and read by the review page, so every repair can
 * be looked at by a person afterwards: the old recipe against the new one, currency by currency,
 * next to the reference.
 */
public record Casebook(
    String source, String pageUrl, String model, String generatedAt, List<Case> cases) {

  /**
   * One recipe applied to one page.
   *
   * @param by who produced the recipe: "recipe", "library", "model" or "human"
   * @param modelReply the model's reply exactly as it came back, for proposals only
   */
  public record Attempt(
      String by,
      int recipeVersion,
      Map<String, String> recipe,
      boolean accepted,
      String error,
      int read,
      int expected,
      int matched,
      List<RecipeVerifier.Reading> readings,
      Long millis,
      Integer promptTokens,
      Integer outputTokens,
      String modelReply) {

    public static Attempt of(String by, SourceSpec.Recipe recipe, RecipeVerifier.Verdict v) {
      return new Attempt(
          by,
          recipe.version(),
          recipe.settings(),
          v.accepted(),
          v.error(),
          v.read(),
          v.expected(),
          v.matched(),
          v.readings(),
          null,
          null,
          null,
          null);
    }

    public static Attempt proposed(
        OllamaRepairer.Proposal p, SourceSpec.Recipe recipe, RecipeVerifier.Verdict v) {
      return new Attempt(
          "model",
          recipe.version(),
          recipe.settings(),
          v.accepted(),
          v.error(),
          v.read(),
          v.expected(),
          v.matched(),
          v.readings(),
          p.millis(),
          p.promptTokens(),
          p.outputTokens(),
          p.raw());
    }
  }

  /**
   * One page the recipe in use could not read.
   *
   * @param snapshot the archive's timestamp for the page, which is also its id
   * @param outcome REUSED, HEALED or HUMAN_NEEDED
   * @param before the recipe in use, failing
   * @param proposals what the model proposed, in order
   * @param after the recipe that read the page exactly, or null if none did
   */
  public record Case(
      String snapshot,
      String archiveUrl,
      String ratesDate,
      String outcome,
      Attempt before,
      List<Attempt> proposals,
      Attempt after) {}
}
