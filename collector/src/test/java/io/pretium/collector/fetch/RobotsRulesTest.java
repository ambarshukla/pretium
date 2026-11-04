package io.pretium.collector.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RobotsRulesTest {

  // The opening of the ECB's real robots.txt, fetched 2026-11-04.
  static final String ECB =
      """
      User-agent: *
      Sitemap: https://www.ecb.europa.eu/sitemap.xml
      Disallow: /*_content.bg.html$
      Disallow: /*_content.en.html$
      """;

  @Test
  void theEcbAllowsItsRatePagesButNotContentFragments() {
    RobotsRules rules = RobotsRules.parse(ECB, "pretium");

    assertThat(
            rules.allows(
                "/stats/policy_and_exchange_rates/euro_reference_exchange_rates/html/index.en.html"))
        .isTrue();
    assertThat(rules.allows("/stats/eurofxref/eurofxref-daily.xml")).isTrue();
    assertThat(rules.allows("/press/pr/html/index_content.en.html")).isFalse();
    // $ anchors the end: the same path with a query string is a different URL.
    assertThat(rules.allows("/press/pr/html/index_content.en.html?x=1")).isTrue();
  }

  @Test
  void aGroupNamingUsReplacesTheWildcardGroup() {
    String body =
        """
        User-agent: *
        Disallow: /

        User-agent: pretium
        Disallow: /private/
        """;
    RobotsRules rules = RobotsRules.parse(body, "pretium");

    assertThat(rules.allows("/stats/rates.xml")).isTrue();
    assertThat(rules.allows("/private/x")).isFalse();
  }

  @Test
  void longestMatchWinsAndAllowWinsATie() {
    String body =
        """
        User-agent: *
        Disallow: /data/
        Allow: /data/public/
        """;
    RobotsRules rules = RobotsRules.parse(body, "pretium");

    assertThat(rules.allows("/data/secret.csv")).isFalse();
    assertThat(rules.allows("/data/public/rates.csv")).isTrue();
  }

  @Test
  void anEmptyDisallowAllowsEverything() {
    assertThat(RobotsRules.parse("User-agent: *\nDisallow:\n", "pretium").allows("/anything"))
        .isTrue();
  }
}
