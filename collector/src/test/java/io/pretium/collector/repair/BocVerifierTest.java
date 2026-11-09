package io.pretium.collector.repair;

import static org.assertj.core.api.Assertions.assertThat;

import io.pretium.collector.source.SourceCatalog;
import io.pretium.collector.source.SourceSpec;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The Bank of Canada's page checked against the Bank's own API: the recipe in use is accepted only
 * because every rate it reads is exactly what the API says, and a recipe that reads the wrong
 * column of the five on the page is not.
 */
class BocVerifierTest {

  static Path fixture(String name) throws Exception {
    return Path.of(BocVerifierTest.class.getResource("/fixtures/" + name).toURI());
  }

  private RecipeVerifier verifier() throws Exception {
    return new RecipeVerifier(
        ReferenceTruth.fromFiles(
            SourceSpec.Format.BOC_VALET_CSV, List.of(fixture("boc-valet-2026-11-03-to-09.csv"))));
  }

  @Test
  void theRecipeInUseReadsTheNewestDayExactly() throws Exception {
    SourceSpec.Recipe recipe = new SourceCatalog().find("boc-daily-html").orElseThrow().recipe();

    RecipeVerifier.Verdict v = verifier().verify(fixture("boc-daily-2026-11-09.html"), recipe);

    assertThat(v.accepted()).isTrue();
    assertThat(v.date()).isEqualTo(LocalDate.of(2026, 11, 9));
    assertThat(v.read()).isEqualTo(24);
    assertThat(v.wrong()).isEmpty();
  }

  @Test
  void aColumnFromAnotherDayIsRejectedByTheDateItClaims() throws Exception {
    Map<String, String> settings =
        new HashMap<>(new SourceCatalog().find("boc-daily-html").orElseThrow().recipe().settings());
    // Rates from the first column (3 November) under the last column's date (9 November).
    settings.put("rateSelector", "td:nth-of-type(1)");

    RecipeVerifier.Verdict v =
        verifier().verify(fixture("boc-daily-2026-11-09.html"), new SourceSpec.Recipe(2, settings));

    assertThat(v.accepted()).isFalse();
    assertThat(v.wrongCount()).isGreaterThan(0);
  }
}
