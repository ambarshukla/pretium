package io.pretium.collector.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

/** A bad spec stops the service at start-up, naming the file and what is wrong with it. */
class SourceCatalogTest {

  static Resource yaml(String name, String body) {
    return new ByteArrayResource(body.getBytes(StandardCharsets.UTF_8)) {
      @Override
      public String getFilename() {
        return name;
      }
    };
  }

  static final String PAGE =
      """
      id: some-page
      name: A page
      publisher: Someone
      licence: open
      url: https://example.test/rates
      format: HTML_RATE_TABLE
      cron: "0 0 16 * * MON-FRI"
      zone: %s
      recipe:
        version: 1
        settings: %s
      """;

  static final String FULL =
      "dateSelector: h2, dateFormat: d MMMM yyyy, rowSelector: tr, currencySelector: td.c, rateSelector: td.r";

  @Test
  void everyShippedSpecLoads() throws Exception {
    SourceCatalog catalog = new SourceCatalog();
    assertThat(catalog.all()).extracting(SourceSpec::id).contains("ecb-reference-html");
    assertThat(catalog.all()).allSatisfy(s -> assertThat(s.licence()).isNotBlank());
  }

  @Test
  void anHtmlSourceMustCarryAWholeRecipe() {
    String noRate =
        PAGE.formatted(
            "Europe/Berlin",
            "{base: EUR, dateSelector: h2, dateFormat: d MMMM yyyy, rowSelector: tr, currencySelector: td.c}");

    assertThatThrownBy(() -> new SourceCatalog(new Resource[] {yaml("some-page.yml", noRate)}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("some-page.yml")
        .hasMessageContaining("rateSelector");
  }

  @Test
  void aRecipeMustSayWhichSideOfThePairTheTableIs() {
    String both = PAGE.formatted("Europe/Berlin", "{base: EUR, quote: CZK, " + FULL + "}");

    assertThatThrownBy(() -> new SourceCatalog(new Resource[] {yaml("some-page.yml", both)}))
        .hasMessageContaining("exactly one of 'base' or 'quote'");
  }

  @Test
  void aTimeZoneIsCheckedBeforeTheScheduleIsBuilt() {
    String badZone = PAGE.formatted("Europe/Prag", "{base: EUR, " + FULL + "}");

    assertThatThrownBy(() -> new SourceCatalog(new Resource[] {yaml("some-page.yml", badZone)}))
        .hasMessageContaining("Europe/Prag");
  }
}
