package io.pretium.collector.repair;

import static org.assertj.core.api.Assertions.assertThat;

import io.pretium.collector.source.SourceSpec;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The verifier against the ECB's real page and real SDMX rates for 3 November 2026. A recipe is
 * accepted only when it reads every rate exactly as the structured channel has it.
 */
class RecipeVerifierTest {

  static final Map<String, String> RECIPE =
      Map.of(
          "base", "EUR",
          "dateSelector", "div.jumbo-box h3",
          "dateFormat", "d MMMM yyyy",
          "rowSelector", "table.forextable tbody tr",
          "currencySelector", "td.currency",
          "rateSelector", "span.rate");

  static Path fixture(String name) throws Exception {
    return Path.of(RecipeVerifierTest.class.getResource("/fixtures/" + name).toURI());
  }

  static RecipeVerifier verifier() throws Exception {
    return new RecipeVerifier(ReferenceTruth.fromSdmxCsv(fixture("ecb-sdmx-2026-11-03.csv")));
  }

  @Test
  void todaysRecipeReadsTodaysPageExactly() throws Exception {
    RecipeVerifier.Verdict v =
        verifier()
            .verify(fixture("ecb-reference-2026-11-03.html"), new SourceSpec.Recipe(1, RECIPE));

    assertThat(v.accepted()).isTrue();
    assertThat(v.date()).isEqualTo(LocalDate.of(2026, 11, 3));
    assertThat(v.wrong()).isEmpty();
    assertThat(v.matched()).isEqualTo(v.read());
  }

  @Test
  void aRecipeThatReadsTheWrongCellIsRejected() throws Exception {
    Map<String, String> wrongCell = new HashMap<>(RECIPE);
    wrongCell.put("rateSelector", "td.alignLeft"); // the currency's name, not its rate

    RecipeVerifier.Verdict v =
        verifier()
            .verify(fixture("ecb-reference-2026-11-03.html"), new SourceSpec.Recipe(2, wrongCell));

    assertThat(v.accepted()).isFalse();
    assertThat(v.error()).contains("not a number");
  }

  @Test
  void aRecipeThatMatchesNothingIsRejectedWithTheSelectorNamed() throws Exception {
    Map<String, String> stale = new HashMap<>(RECIPE);
    stale.put("rowSelector", "table.rates tr");

    RecipeVerifier.Verdict v =
        verifier()
            .verify(fixture("ecb-reference-2026-11-03.html"), new SourceSpec.Recipe(2, stale));

    assertThat(v.accepted()).isFalse();
    assertThat(v.error()).contains("table.rates tr");
  }

  /**
   * The page as archived on 26 January 2017 (Internet Archive): no jumbo-box, a differently named
   * table, and the date inside a heading. Today's recipe fails on it; a recipe written for that
   * layout, with a dateRegex, reads all 31 currencies exactly as the SDMX history has them.
   */
  @Test
  void theArchived2017LayoutNeedsADifferentRecipe() throws Exception {
    RecipeVerifier verifier =
        new RecipeVerifier(ReferenceTruth.fromSdmxCsv(fixture("ecb-sdmx-2017-01-26.csv")));
    Path page = fixture("ecb-reference-2017-01-26-archived.html");

    assertThat(verifier.verify(page, new SourceSpec.Recipe(1, RECIPE)).error())
        .contains("div.jumbo-box h3");

    Map<String, String> v2 = new HashMap<>(RECIPE);
    v2.put("dateSelector", "h2");
    v2.put("dateRegex", ":\s*(.+)$");
    v2.put("rowSelector", "table.ecb-forexTable tbody tr");
    RecipeVerifier.Verdict v = verifier.verify(page, new SourceSpec.Recipe(2, v2));

    assertThat(v.accepted()).as(String.valueOf(v.error())).isTrue();
    assertThat(v.date()).isEqualTo(LocalDate.of(2017, 1, 26));
    assertThat(v.matched()).isEqualTo(31);
  }
}
