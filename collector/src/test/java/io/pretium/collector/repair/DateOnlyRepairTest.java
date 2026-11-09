package io.pretium.collector.repair;

import static org.assertj.core.api.Assertions.assertThat;

import io.pretium.collector.source.SourceCatalog;
import io.pretium.collector.source.SourceSpec;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The CNB's page as archived on 1 July 2025, with the CNB's real rates for that day. Its table is
 * laid out as today's recipe expects, but the line announcing the date is worded differently, so
 * the recipe fails on the date alone.
 */
class DateOnlyRepairTest {

  static Path fixture(String name) throws Exception {
    return Path.of(DateOnlyRepairTest.class.getResource("/fixtures/" + name).toURI());
  }

  private final SourceSpec.Recipe inUse;

  DateOnlyRepairTest() throws Exception {
    inUse = new SourceCatalog().find("cnb-fixing-html").orElseThrow().recipe();
  }

  private RecipeVerifier verifier() throws Exception {
    return new RecipeVerifier(
        ReferenceTruth.fromFiles(
            SourceSpec.Format.CNB_FIXING_TXT,
            List.of(fixture("cnb-fixing-year-2025-07-01-excerpt.txt"))));
  }

  @Test
  void replacingOnlyTheDateSettingsHealsAPageWhoseDateLineChanged() throws Exception {
    Path page = fixture("cnb-fixing-2025-07-01-archived.html");
    assertThat(verifier().verify(page, inUse).accepted()).isFalse();

    SourceSpec.Recipe fixed =
        DateOnlyRepair.apply(inUse, PageDigest.dateHint(page), inUse.version() + 1);

    assertThat(fixed).isNotNull();
    // The table selectors are exactly the ones in use; only the date settings differ.
    for (String key :
        List.of("rowSelector", "currencySelector", "rateSelector", "amountSelector")) {
      assertThat(fixed.get(key, null)).isEqualTo(inUse.get(key, null));
    }
    assertThat(fixed.get("dateSelector", null)).isNotEqualTo(inUse.get("dateSelector", null));
    RecipeVerifier.Verdict v = verifier().verify(page, fixed);
    assertThat(v.accepted()).isTrue();
    assertThat(v.date()).isEqualTo(LocalDate.of(2025, 7, 1));
    assertThat(v.wrong()).isEmpty();
  }

  @Test
  void nothingToDoWhenThereIsNoDateOrNothingWouldChange() {
    assertThat(DateOnlyRepair.apply(inUse, null, 2)).isNull();
    PageDigest.DateHint same =
        new PageDigest.DateHint(
            inUse.get("dateSelector", null),
            inUse.get("dateFormat", null),
            inUse.get("dateRegex", null),
            "");
    assertThat(DateOnlyRepair.apply(inUse, same, 2)).isNull();
  }
}
