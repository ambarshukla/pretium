package io.pretium.collector.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.pretium.collector.fetch.FetchResult;
import io.pretium.collector.fetch.PoliteFetcher;
import io.pretium.collector.source.SourceCatalog;
import io.pretium.collector.source.SourceSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/**
 * Finding the World Bank's current remittance file through its data catalogue. The catalogue entry
 * is a real response from {@code ddh-openapi.worldbank.org} captured on 5 November 2026 (see
 * docs/PROVENANCE.md), when the current file was the Q3 2025 one.
 */
class UrlDiscoveryTest {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final String Q3_2025 =
      "https://datacatalogfiles.worldbank.org/ddh-published/0037898/DR0095523/rpw_dataset_2011_2025_q3.xlsx";

  @TempDir Path downloads;

  static Path fixture(String name) throws Exception {
    return Path.of(UrlDiscoveryTest.class.getResource("/fixtures/" + name).toURI());
  }

  static SourceSpec rpw() throws Exception {
    return new SourceCatalog().find("wb-rpw").orElseThrow();
  }

  @Test
  void theSpecAsksTheCatalogue() throws Exception {
    SourceSpec.Discovery discovery = rpw().discovery();

    assertThat(discovery).isNotNull();
    assertThat(discovery.catalogUrl())
        .isEqualTo("https://ddh-openapi.worldbank.org/datasets/0037898");
    assertThat(discovery.resourceName())
        .isEqualTo("Remittance Prices Worldwide (Complete Dataset)");
  }

  @Test
  void theCatalogueNamesTheCurrentFile() throws Exception {
    PoliteFetcher fetcher = mock(PoliteFetcher.class);
    when(fetcher.fetch(anyString(), any()))
        .thenAnswer(
            call -> {
              Path copy = Files.createTempFile(downloads, "catalogue-", ".json");
              Files.copy(
                  fixture("wb-ddh-dataset-0037898.json"),
                  copy,
                  StandardCopyOption.REPLACE_EXISTING);
              return new FetchResult(FetchResult.Outcome.OK, 200, copy, null, null);
            });

    assertThat(new UrlDiscovery(fetcher, JSON).urlFor(rpw())).isEqualTo(Q3_2025);
    verify(fetcher)
        .fetch("https://ddh-openapi.worldbank.org/datasets/0037898", java.util.Optional.empty());
  }

  /** Rate-limited (the catalogue did answer 429 several times on 5 November): keep collecting. */
  @Test
  void aFailedLookupFallsBackToTheSpecsUrl() throws Exception {
    PoliteFetcher fetcher = mock(PoliteFetcher.class);
    when(fetcher.fetch(anyString(), any()))
        .thenReturn(new FetchResult(FetchResult.Outcome.FAILED, 429, null, null, "HTTP 429"));

    SourceSpec spec = rpw();
    assertThat(new UrlDiscovery(fetcher, JSON).urlFor(spec)).isEqualTo(spec.url());
  }

  @Test
  void aFixedSourceIsNotLookedUp() throws Exception {
    PoliteFetcher fetcher = mock(PoliteFetcher.class);
    SourceSpec ecb = new SourceCatalog().find("ecb-eurofxref-daily").orElseThrow();

    assertThat(new UrlDiscovery(fetcher, JSON).urlFor(ecb)).isEqualTo(ecb.url());
    verify(fetcher, never()).fetch(anyString(), any());
  }

  @Test
  void onlyTheNamedResourceOnTheExpectedHostIsAccepted() throws Exception {
    var catalogue = JSON.readTree(Files.readString(fixture("wb-ddh-dataset-0037898.json")));

    assertThat(
            UrlDiscovery.find(catalogue, "Remittance Prices Worldwide (Complete Dataset)", Q3_2025))
        .contains(Q3_2025);
    // The quarterly report is in the same list; asking for the dataset must not return it.
    assertThat(UrlDiscovery.find(catalogue, "Remittance Prices Worldwide (Corridors)", Q3_2025))
        .isEmpty();
    // A catalogue entry pointing at a host other than the source's own is ignored.
    assertThat(
            UrlDiscovery.find(
                catalogue,
                "Remittance Prices Worldwide (Complete Dataset)",
                "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml"))
        .isEmpty();
  }
}
